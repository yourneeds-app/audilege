package app.yourneeds.foliocue

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONArray
import org.json.JSONObject

internal data class Profile(
    val id: String = UUID.randomUUID().toString(),
    val label: String,
    val server: String,
    val user: String,
    val localHttp: Boolean = false,
    val accountLogin: Boolean = false,
) {
    val api: URI get() {
        val address = URI(server.trim().trimEnd('/'))
        require(address.host != null && address.userInfo == null && address.query == null && address.fragment == null) {
            "Gib eine vollständige BookOrbit-Adresse ein."
        }
        require(address.scheme == "https" || (address.scheme == "http" && localHttp && localHost(address.host))) {
            "Verwende HTTPS oder erlaube HTTP ausdrücklich für eine lokale Adresse."
        }
        val base = address.toString().removeSuffix("/api/v1/opds").removeSuffix("/api/v1")
        return URI("$base/api/v1")
    }
    val catalog: URI get() = URI(if (accountLogin) "$api/books/query" else "$api/opds")
}

private fun localHost(host: String): Boolean {
    val bytes = host.split('.').mapNotNull { it.toIntOrNull()?.takeIf { value -> value in 0..255 } }
    return host.endsWith(".local", ignoreCase = true) || (bytes.size == 4 &&
        (bytes[0] == 10 || bytes[0] == 127 || bytes.take(2) == listOf(192, 168) ||
            (bytes[0] == 172 && bytes[1] in 16..31)))
}

internal data class CatalogEntry(val title: String, val url: URI, val book: Boolean, val author: String = "")
internal data class CatalogPage(val entries: List<CatalogEntry>, val next: URI?)
internal class CatalogAccessException(val messageId: Int) : IllegalStateException()
internal interface LibraryCatalog {
    fun page(url: URI): CatalogPage
    fun download(url: URI, destination: File): File
}

internal class Catalog(private val profile: Profile, password: String) : LibraryCatalog {
    private val origin = profile.catalog
    private val credentials = Base64.encodeToString("${profile.user}:$password".toByteArray(), Base64.NO_WRAP)

    private fun request(url: URI): HttpURLConnection {
        require(url.scheme == origin.scheme && url.host.equals(origin.host, ignoreCase = true) &&
            url.port == origin.port && url.userInfo == null) { "Der Link verweist auf einen anderen Server." }
        return (url.toURL().openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            connectTimeout = 9000
            readTimeout = 25000
            setRequestProperty("Authorization", "Basic $credentials")
        }
    }

    private fun <T> get(url: URI, read: (InputStream) -> T): T {
        val connection = request(url)
        try {
            when (val status = connection.responseCode) {
                200 -> return connection.inputStream.use(read)
                401, 403 -> throw CatalogAccessException(R.string.auth_error)
                404 -> throw CatalogAccessException(R.string.setup_error)
                else -> error("BookOrbit antwortet mit HTTP $status.")
            }
        } finally { connection.disconnect() }
    }

    override fun page(url: URI): CatalogPage = get(url) { stream ->
        val feed = xml(stream)
        require(feed.localName == "feed") { "Der Server hat keinen OPDS-Katalog geliefert." }
        val entries = feed.children("entry").flatMap { entry ->
            val title = entry.children("title").firstOrNull()?.textContent?.trim().orEmpty()
            entry.children("link").mapNotNull { link ->
                val rel = link.getAttribute("rel")
                val type = link.getAttribute("type")
                val book = rel.startsWith("http://opds-spec.org/acquisition") && type.substringBefore(';') == "application/epub+zip"
                val section = type.startsWith("application/atom+xml") && rel !in listOf("self", "related")
                if ((!book && !section) || title.isBlank()) return@mapNotNull null
                CatalogEntry(title, url.resolve(link.getAttribute("href")), book)
            }.distinct()
        }
        val next = feed.children("link").firstOrNull { it.getAttribute("rel") == "next" }
            ?.getAttribute("href")?.let(url::resolve)
        CatalogPage(entries, next)
    }

    override fun download(url: URI, destination: File) = get(url) { stream ->
        savePublication(stream, destination)
    }
}

internal fun savePublication(stream: InputStream, destination: File): File {
    destination.parentFile?.mkdirs()
    val partial = File.createTempFile("download-", ".part", destination.parentFile)
    try {
        partial.outputStream().use { out -> stream.copyTo(out) }
        Publication(partial)
        java.nio.file.Files.move(partial.toPath(), destination.toPath(),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        return destination
    } finally { partial.delete() }
}

internal class Profiles(context: Context) {
    private val prefs = context.getSharedPreferences("profiles", Context.MODE_PRIVATE)

    fun list(): List<Profile> {
        val entries = JSONArray(prefs.getString("entries", "[]"))
        return (0 until entries.length()).map { index ->
            val p = entries.getJSONObject(index)
            Profile(p.getString("id"), p.getString("label"), p.getString("server"), p.getString("user"),
                p.optBoolean("localHttp"), p.optBoolean("accountLogin"))
        }
    }

    fun save(profile: Profile, credential: String) {
        val entries = JSONArray((list().filterNot { it.id == profile.id } + profile).map {
            JSONObject().put("id", it.id).put("label", it.label).put("server", it.server)
                .put("user", it.user).put("localHttp", it.localHttp).put("accountLogin", it.accountLogin)
        })
        check(prefs.edit().putString("entries", entries.toString())
            .putString(profile.id, encrypt(credential)).commit())
    }

    fun renew(profile: Profile, credential: String) {
        check(prefs.edit().putString(profile.id, encrypt(credential)).commit())
    }

    private fun encrypt(credential: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        return Base64.encodeToString(cipher.iv + cipher.doFinal(credential.toByteArray()), Base64.NO_WRAP)
    }

    fun updateToken(): String = prefs.getString("github-release-token", null)?.let(::decrypt).orEmpty()

    fun saveUpdateToken(token: String) {
        val editor = prefs.edit()
        if (token.isBlank()) editor.remove("github-release-token")
        else editor.putString("github-release-token", encrypt(token))
        check(editor.commit())
    }

    fun credential(profile: Profile): String = decrypt(prefs.getString(profile.id, null) ?: error("Der Zugang fehlt."))

    private fun decrypt(encoded: String): String {
        val value = Base64.decode(encoded, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, value.copyOfRange(0, 12)))
        }
        return cipher.doFinal(value.copyOfRange(12, value.size)).decodeToString()
    }

    private fun key(): SecretKey {
        val alias = "foliocue-opds"
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey(alias, null) as? SecretKey) ?: KeyGenerator.getInstance("AES", "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
    }
}
