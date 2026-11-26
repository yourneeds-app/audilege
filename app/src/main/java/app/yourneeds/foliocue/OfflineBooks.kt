package app.yourneeds.foliocue

import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.Network
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.withContext

/** Download filenames predate BookOrbit progress sync. Record the server edition alongside each copy. */
internal object OfflineBooks {
    fun isPodcast(prefs: SharedPreferences, file: File): Boolean = prefs.contains("podcast.${keyOf(file.absolutePath)}")

    fun rememberPodcast(prefs: SharedPreferences, file: File, entry: CatalogEntry): Boolean {
        if (entry.category != "podcast") return false
        val key = "podcast.${keyOf(file.absolutePath)}"
        val data = org.json.JSONObject().put("show", entry.showTitle).put("key", entry.showKey)
            .put("published", entry.added).toString()
        if (prefs.getString(key, null) == data) return false
        prefs.edit().putString(key, data).apply()
        return true
    }

    fun localEntry(prefs: SharedPreferences, file: File, publication: Publication): CatalogEntry {
        val entry = CatalogEntry(publication.title, file.toURI(), true, publication.author)
        val data = prefs.getString("podcast.${keyOf(file.absolutePath)}", null)
        if (data != null) {
            val show = org.json.JSONObject(data)
            return entry.copy(category = "podcast", showKey = show.optString("key"), showTitle = show.optString("show"),
                added = show.optLong("published").takeIf { it > 0 })
        }
        return entry.copy(category = if (publication.spine.any { it.overlay.isNotBlank() }) "audilege" else "ebook")
    }

    fun matchExisting(preferences: SharedPreferences, folder: File, entries: List<CatalogEntry>): Boolean {
        val local = folder.list()?.toSet().orEmpty()
        if (local.none { it.endsWith(".epub") }) return false
        var matched = false
        for (entry in entries) {
            val fileId = entry.editionFileId ?: continue
            val bookId = entry.url.path.substringAfterLast('/').toLongOrNull() ?: continue
            val filename = keyOf("${entry.url}#$fileId") + ".epub"
            if (filename !in local) continue
            val file = File(folder, filename)
            if (rememberPodcast(preferences, file, entry)) matched = true
            val key = keyOf(file.absolutePath)
            if (preferences.getString("edition.folder.$key", null) == folder.absolutePath) continue
            associate(preferences, file, OrbitBook(bookId, fileId, "", ""))
            matched = true
        }
        return matched
    }

    fun associate(preferences: SharedPreferences, file: File, detail: OrbitBook) {
        val bookKey = keyOf(file.absolutePath)
        preferences.edit().putString("edition.$bookKey", "${detail.id}:${detail.fileId}")
            .putString("edition.folder.$bookKey", file.parent).apply()
    }

    fun online(preferences: SharedPreferences, file: File, catalog: OrbitCatalog?): OnlineBook? =
        online(preferences, keyOf(file.absolutePath), catalog)

    private fun online(preferences: SharedPreferences, bookKey: String, catalog: OrbitCatalog?): OnlineBook? {
        if (catalog == null) return null
        val saved = preferences.getString("edition.$bookKey", null)?.split(':') ?: return null
        if (saved.size != 2) return null
        val id = saved[0].toLongOrNull()?.takeIf { it > 0 } ?: return null
        val fileId = saved[1].toLongOrNull()?.takeIf { it > 0 } ?: return null
        return OnlineBook(catalog, OrbitBook(id, fileId, "", "", podcast = preferences.contains("podcast.$bookKey")))
    }

    suspend fun flush(preferences: SharedPreferences, folder: File, catalog: OrbitCatalog) = withContext(Dispatchers.IO) {
        val pendingKeys = preferences.all.keys.filter { it.endsWith(".sync.pending") }
        for (name in pendingKeys) {
            val key = name.removeSuffix(".sync.pending")
            if (preferences.getString("edition.folder.$key", null) != folder.absolutePath) continue
            val online = online(preferences, key, catalog) ?: continue
            try {
                ReadingSync.open(online, null, preferences, key).flush()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                // Retain pending progress when the server is unreachable; resume on the next connection.
            }
        }
    }
}

@Composable
internal fun SyncPendingEditions(preferences: SharedPreferences, folder: File, catalog: OrbitCatalog?) {
    val context = LocalContext.current.applicationContext
    LaunchedEffect(catalog, folder) {
        if (catalog == null) return@LaunchedEffect
        val manager = context.getSystemService(ConnectivityManager::class.java)
        callbackFlow {
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { trySend(Unit) }
            }
            manager.registerDefaultNetworkCallback(callback)
            awaitClose { manager.unregisterNetworkCallback(callback) }
        }.conflate().collect { OfflineBooks.flush(preferences, folder, catalog) }
    }
}
