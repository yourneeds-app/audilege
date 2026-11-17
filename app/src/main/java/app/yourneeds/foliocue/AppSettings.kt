package app.yourneeds.foliocue

import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import java.net.HttpURLConnection
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
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

private const val RELEASES = "https://github.com/yourneeds-app/audilege/releases"
private data class Release(val version: String, val link: String)
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

@Composable
internal fun SettingsScreen(
    prefs: SharedPreferences, store: Profiles, paper: Paper, accent: Accent,
    setPaper: (Paper) -> Unit, setAccent: (Accent) -> Unit, close: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val installed = remember { context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty() }
    var savedToken by remember { mutableStateOf(runCatching { store.updateToken() }.getOrDefault("")) }
    var input by remember { mutableStateOf("") }
    var checking by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<Int?>(null) }
    var available by remember { mutableStateOf<Release?>(null) }
    var font by remember { mutableIntStateOf(prefs.getInt("font", 0).coerceIn(Typeface.entries.indices)) }
    var size by remember { mutableIntStateOf(prefs.getInt("size", 100).coerceIn(75, 200)) }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = close) { Text(stringResource(R.string.back)) }
            Text(stringResource(R.string.app_settings), style = MaterialTheme.typography.headlineMedium)
            Choice(stringResource(R.string.theme), Paper.entries.map { it.label }, paper.ordinal) { setPaper(Paper.entries[it]) }
            Choice(stringResource(R.string.accent), Accent.entries.map { it.label }, accent.ordinal) { setAccent(Accent.entries[it]) }
            Choice(stringResource(R.string.font), Typeface.entries.map { it.label }, font) {
                font = it
                prefs.edit().putInt("font", font).apply()
            }
            Text("${stringResource(R.string.size)} · $size %")
            Slider(size.toFloat(), { size = it.toInt(); prefs.edit().putInt("size", size).apply() },
                modifier = Modifier.widthIn(max = 480.dp), valueRange = 75f..200f)
            HorizontalDivider()
            Text(stringResource(R.string.updates), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.current_version, installed))
            if (savedToken.isEmpty() || input.isNotBlank()) Text(stringResource(R.string.github_token_hint))
            OutlinedTextField(input, { input = it }, modifier = Modifier.fillMaxWidth().widthIn(max = 520.dp),
                singleLine = true, enabled = !checking, visualTransformation = PasswordVisualTransformation(),
                label = { Text(stringResource(R.string.github_token)) },
                supportingText = if (savedToken.isNotEmpty()) {
                    { Text(stringResource(R.string.password_saved_hint)) }
                } else null,
            )
            Button(enabled = !checking, onClick = {
                val token = input.trim().ifBlank { savedToken }
                available = null
                message = null
                if (token.isBlank()) {
                    message = R.string.update_missing
                    return@Button
                }
                checking = true
                scope.launch {
                    try {
                        val release = withContext(Dispatchers.IO) {
                            checkRelease(token).also { if (token != savedToken) store.saveUpdateToken(token) }
                        }
                        savedToken = token
                        input = ""
                        if (newerVersion(release.version, installed)) available = release
                        else message = R.string.up_to_date
                    } catch (failure: CancellationException) {
                        throw failure
                    } catch (failure: UpdateAccessDenied) {
                        message = R.string.update_access
                    } catch (failure: Exception) {
                        message = R.string.update_error
                    } finally {
                        checking = false
                    }
                }
            }) { Text(stringResource(if (checking) R.string.connecting else R.string.check_updates)) }
            message?.let { Text(stringResource(it)) }
            if (available == null) TextButton(onClick = {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(RELEASES))) }
                    .onFailure { message = R.string.update_error }
            }) { Text(stringResource(R.string.open_releases)) }
            available?.let { release ->
                Text(stringResource(R.string.release_available, release.version))
                OutlinedButton(onClick = {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(release.link))) }
                        .onFailure { message = R.string.update_error }
                }) { Text(stringResource(R.string.open_update)) }
            }
        }
    }
}

