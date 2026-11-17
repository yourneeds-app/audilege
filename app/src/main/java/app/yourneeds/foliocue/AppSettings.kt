package app.yourneeds.foliocue

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import java.net.HttpURLConnection
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal enum class Accent(val label: Int, val dark: Long, val light: Long, val highlight: Long) {
    Teal(R.string.teal, 0xFF145A62, 0xFF8DDFE3, 0xFFA9E1D8),
    Clay(R.string.clay, 0xFF8B4133, 0xFFFFB4A4, 0xFFFFC4AD),
    Iris(R.string.iris, 0xFF4C468C, 0xFFC8C0FF, 0xFFDDD2FF),
}

internal fun appearanceColors(paper: Paper, accent: Accent): ColorScheme {
    val background = Color(paper.bg)
    val foreground = Color(paper.ink)
    return if (paper == Paper.Night) darkColorScheme(
        primary = Color(accent.light), onPrimary = Color(0xFF172330),
        background = background, surface = background, onBackground = foreground, onSurface = foreground,
    ) else lightColorScheme(
        primary = Color(accent.dark), onPrimary = Color.White, background = background,
        surface = background, onBackground = foreground, onSurface = foreground,
    )
}

internal fun SharedPreferences.paper() = Paper.entries.getOrElse(getInt("paper", 0)) { Paper.Day }

internal const val RELEASES = "https://github.com/yourneeds-app/audilege/releases"
internal data class Release(val version: String, val link: String)
private class UpdateAccessDenied : Exception()

private fun checkRelease(token: String): Release {
    val url = URI("https://api.github.com/repos/yourneeds-app/audilege/releases/latest").toURL()
    val request = url.openConnection() as HttpURLConnection
    try {
        request.instanceFollowRedirects = false
        request.connectTimeout = 8000
        request.readTimeout = 8000
        request.setRequestProperty("Accept", "application/vnd.github+json")
        request.setRequestProperty("Authorization", "Bearer $token")
        val status = request.responseCode
        if (status in setOf(401, 403, 404)) throw UpdateAccessDenied()
        check(status == 200)
        val response = request.inputStream.bufferedReader().use { JSONObject(it.readText()) }
        val tag = response.getString("tag_name")
        val version = tag.removePrefix("v")
        require(version.matches(Regex("""\d+\.\d+\.\d+""")))
        val assets = response.getJSONArray("assets")
        val apk = "Audilege-$version-debug.apk"
        check((0 until assets.length()).any { assets.optJSONObject(it)?.optString("name") == apk })
        return Release(version, "$RELEASES/download/${Uri.encode(tag)}/$apk")
    } finally {
        request.disconnect()
    }
}

private fun newerVersion(candidate: String, installed: String): Boolean {
    val left = candidate.split('.').map { it.toIntOrNull() ?: return false }
    val right = installed.split('.').map { it.toIntOrNull() ?: return false }
    for (index in 0 until maxOf(left.size, right.size)) {
        val diff = left.getOrElse(index) { 0 }.compareTo(right.getOrElse(index) { 0 })
        if (diff != 0) return diff > 0
    }
    return false
}

internal class AppUpdates(private val store: Profiles, val installed: String, private val scope: CoroutineScope) {
    private var token = runCatching { store.updateToken() }.getOrDefault("")
    var configured by mutableStateOf(token.isNotBlank())
        private set
    var checking by mutableStateOf(false)
        private set
    var status by mutableStateOf<Int?>(null)
        private set
    var available by mutableStateOf<Release?>(null)
        private set

    fun check(candidate: String = token, automatic: Boolean = false) {
        if (checking || (automatic && candidate.isBlank())) return
        if (candidate.isBlank()) {
            status = R.string.update_missing
            return
        }
        checking = true
        status = null
        scope.launch {
            try {
                val release = withContext(Dispatchers.IO) {
                    checkRelease(candidate).also { if (token != candidate) store.saveUpdateToken(candidate) }
                }
                token = candidate
                configured = true
                available = release.takeIf { newerVersion(it.version, installed) }
                if (available == null) status = R.string.up_to_date
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: UpdateAccessDenied) {
                status = R.string.update_access
            } catch (_: Exception) {
                status = R.string.update_error
            } finally {
                checking = false
            }
        }
    }

    fun clearAccess() {
        if (checking) return
        checking = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) { store.saveUpdateToken("") }
                token = ""
                configured = false
                available = null
                status = null
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                status = R.string.update_error
            } finally {
                checking = false
            }
        }
    }
}

private fun Context.openUpdate(url: String) {
    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScreen(
    prefs: SharedPreferences, updates: AppUpdates, paper: Paper, accent: Accent,
    setPaper: (Paper) -> Unit, setAccent: (Accent) -> Unit, close: () -> Unit,
) {
    val context = LocalContext.current
    var accessDialog by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    var browserFailed by remember { mutableStateOf(false) }
    fun open(url: String) { browserFailed = runCatching { context.openUpdate(url) }.isFailure }
    var font by remember { mutableIntStateOf(prefs.getInt("font", 0).coerceIn(Typeface.entries.indices)) }
    var size by remember { mutableIntStateOf(prefs.getInt("size", 100).coerceIn(75, 200)) }

    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.app_settings)) }, navigationIcon = {
            IconButton(onClick = close) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
        })
    }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 600.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.updates), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.current_version, updates.installed))
            Text(stringResource(if (updates.configured) R.string.update_check_automatic else R.string.update_browser_hint),
                style = MaterialTheme.typography.bodyMedium)
            Button(enabled = !updates.checking, onClick = {
                if (updates.configured) updates.check() else open("$RELEASES/latest")
            }) { Text(stringResource(R.string.check_updates)) }
            if (updates.checking) LinearProgressIndicator(Modifier.fillMaxWidth())
            Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                updates.available?.let { release ->
                    Text(stringResource(R.string.release_available, release.version))
                    OutlinedButton(onClick = { open(release.link) }) { Text(stringResource(R.string.open_update)) }
                }
                updates.status?.let { Text(stringResource(it)) }
                if (browserFailed) Text(stringResource(R.string.update_error))
            }
            if (updates.configured) TextButton(onClick = { open("$RELEASES/latest") }) {
                Text(stringResource(R.string.open_releases))
            }
            TextButton(enabled = !updates.checking, onClick = { accessDialog = true }) {
                Text(stringResource(if (updates.configured) R.string.update_token_change else R.string.update_token_configure))
            }
            HorizontalDivider()
            Text(stringResource(R.string.material_display), style = MaterialTheme.typography.titleLarge)
            Choice(stringResource(R.string.theme), Paper.entries.map { it.label }, paper.ordinal) { setPaper(Paper.entries[it]) }
            Choice(stringResource(R.string.accent), Accent.entries.map { it.label }, accent.ordinal) { setAccent(Accent.entries[it]) }
            Choice(stringResource(R.string.font), Typeface.entries.map { it.label }, font) {
                font = it
                prefs.edit().putInt("font", font).apply()
            }
            Text("${stringResource(R.string.size)} · $size %")
            Slider(size.toFloat(), { size = it.toInt(); prefs.edit().putInt("size", size).apply() },
                modifier = Modifier.widthIn(max = 480.dp), valueRange = 75f..200f)

        }
        }
    }
    if (accessDialog) AlertDialog(
        onDismissRequest = { accessDialog = false; input = "" },
        title = { Text(stringResource(R.string.github_token)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.github_token_hint))
                OutlinedTextField(input, { input = it }, modifier = Modifier.fillMaxWidth(),
                    singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    label = { Text(stringResource(R.string.github_token)) })
                if (updates.configured) TextButton(onClick = {
                    updates.clearAccess()
                    accessDialog = false
                    input = ""
                }) { Text(stringResource(R.string.update_token_remove)) }
            }
        },
        confirmButton = {
            TextButton(enabled = input.isNotBlank(), onClick = {
                updates.check(input.trim())
                accessDialog = false
                input = ""
            }) { Text(stringResource(R.string.update_token_save)) }
        },
        dismissButton = { TextButton(onClick = { accessDialog = false; input = "" }) { Text(stringResource(R.string.cancel)) } },
    )
}
