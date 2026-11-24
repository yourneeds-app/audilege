package app.yourneeds.foliocue

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.text.DateFormat
import java.util.Date

internal const val RELEASES = "https://github.com/yourneeds-app/audilege/releases"
internal data class Release(val version: String, val link: String)
private class ReleaseHttpError(val status: Int) : Exception()
private const val AUTO_INTERVAL = 6 * 60 * 60 * 1000L
private val versionPattern = Regex("""\d+\.\d+\.\d+""")

internal fun parseRelease(response: JSONObject): Release {
    val tag = response.getString("tag_name")
    val version = tag.removePrefix("v")
    require(version.matches(versionPattern) && !response.optBoolean("draft") && !response.optBoolean("prerelease"))
    val assets = response.getJSONArray("assets")
    val names = (0 until assets.length()).mapNotNull { assets.optJSONObject(it)?.optString("name") }
    val file = listOf("Audilege-$version.apk", "Audilege-$version-release.apk", "Audilege-$version-debug.apk").first { it in names }
    return Release(version, "$RELEASES/download/$tag/$file")
}

private fun fetchRelease(token: String): Release {
    val request = URI("https://api.github.com/repos/yourneeds-app/audilege/releases/latest").toURL().openConnection() as HttpURLConnection
    try {
        request.instanceFollowRedirects = false
        request.connectTimeout = 8000
        request.readTimeout = 8000
        request.setRequestProperty("Accept", "application/vnd.github+json")
        request.setRequestProperty("User-Agent", "Audilege-Android")
        if (token.isNotBlank()) request.setRequestProperty("Authorization", "Bearer $token")
        val status = request.responseCode
        if (status != 200) throw ReleaseHttpError(status)
        return request.inputStream.bufferedReader().use { parseRelease(JSONObject(it.readText())) }
    } finally {
        request.disconnect()
    }
}

internal fun newerVersion(candidate: String, installed: String): Boolean {
    if (!versionPattern.matches(candidate) || !versionPattern.matches(installed)) return false
    val left = candidate.split('.').map { it.toIntOrNull() ?: return false }
    val right = installed.split('.').map { it.toIntOrNull() ?: return false }
    for (index in left.indices) if (left[index] != right[index]) return left[index] > right[index]
    return false
}

internal class AppUpdates(
    private val store: Profiles, val installed: String, private val scope: CoroutineScope,
    private val prefs: SharedPreferences,
) {
    private var token = runCatching { store.updateToken() }.getOrDefault("")
    var configured by mutableStateOf(token.isNotBlank())
        private set
    var checking by mutableStateOf(false)
        private set
    var status by mutableStateOf<Int?>(null)
        private set
    var lastCheck by mutableLongStateOf(prefs.getLong("updateLastSuccess", 0L))
        private set
    var automaticEnabled by mutableStateOf(prefs.getBoolean("updateAuto", true))
        private set
    var available by mutableStateOf<Release?>(null)
        private set

    init {
        val version = prefs.getString("updateVersion", null)
        val link = prefs.getString("updateLink", null)
        if (version != null && link != null && newerVersion(version, installed) && link.startsWith("$RELEASES/")) {
            available = Release(version, link)
        }
    }

    fun configureAutomatic(enabled: Boolean) {
        automaticEnabled = enabled
        prefs.edit().putBoolean("updateAuto", enabled).apply()
        if (enabled) check(automatic = true)
    }

    fun check(candidate: String = token, automatic: Boolean = false) {
        if (checking) return
        val now = System.currentTimeMillis()
        if (automatic && (!automaticEnabled || now - lastCheck in 0L until AUTO_INTERVAL)) return
        checking = true
        status = null
        scope.launch {
            try {
                val release = withContext(Dispatchers.IO) {
                    // Existing private installations keep their access. An expired stored credential
                    // should still allow a public repository to be checked.
                    try { fetchRelease(candidate) }
                    catch (error: ReleaseHttpError) {
                        if (candidate.isBlank() || candidate != token || error.status !in setOf(401, 403, 404)) throw error
                        fetchRelease("")
                    }.also { if (candidate != token) store.saveUpdateToken(candidate) }
                }
                token = candidate
                configured = candidate.isNotBlank()
                available = release.takeIf { newerVersion(it.version, installed) }
                lastCheck = System.currentTimeMillis()
                prefs.edit().putLong("updateLastSuccess", lastCheck).putString("updateVersion", release.version)
                    .putString("updateLink", release.link).apply()
                if (available == null) status = R.string.up_to_date
            } catch (failure: CancellationException) {
                throw failure
            } catch (error: ReleaseHttpError) {
                status = when (error.status) {
                    404 -> R.string.design_release_missing
                    403, 429 -> R.string.design_release_limited
                    401 -> R.string.update_access
                    else -> R.string.update_error
                }
            } catch (_: Exception) {
                status = R.string.update_error
            } finally { checking = false }
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
                status = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                status = R.string.update_error
            } finally { checking = false }
        }
    }
}

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

@Composable
private fun SettingsGroup(title: Int, selected: Boolean, choose: () -> Unit, content: @Composable () -> Unit) {
    val state = stringResource(if (selected) R.string.design_expanded else R.string.design_collapsed)
    OutlinedCard(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = choose).semantics { stateDescription = state }.heightIn(min = 56.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(title), Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleMedium)
            Text(if (selected) "−" else "+", style = MaterialTheme.typography.titleLarge)
        }
        if (selected) Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }
    }
}

@Composable
private fun ToggleRow(title: Int, selected: Boolean, choose: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(value = selected, role = Role.Switch, onValueChange = choose).heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(title), Modifier.weight(1f))
        Switch(checked = selected, onCheckedChange = null)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScreen(
    prefs: SharedPreferences, updates: AppUpdates, accent: Accent, setAccent: (Accent) -> Unit,
    onProfiles: (() -> Unit)? = null, close: () -> Unit,
) {
    val context = LocalContext.current
    val display = remember(context) { context.getSharedPreferences("display", Context.MODE_PRIVATE) }
    var expanded by rememberSaveable { mutableIntStateOf(if (updates.available != null) R.string.updates else 0) }
    var accessDialog by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    var browserFailed by remember { mutableStateOf(false) }
    val mode = watchPreference(prefs, setOf("appTheme")) { prefs.getInt("appTheme", 0).coerceIn(0, 2) }
    val adaptive = watchPreference(prefs, setOf("coverAccent")) { prefs.getBoolean("coverAccent", true) }
    val skip = watchPreference(prefs, setOf("skipSeconds")) { prefs.getInt("skipSeconds", 15) }
    val language = watchPreference(display, setOf("language")) { display.getString("language", "").orEmpty() }
    fun open(url: String) {
        browserFailed = runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }.isFailure
    }
    fun expand(id: Int) { expanded = if (expanded == id) 0 else id }
    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.app_settings)) }, navigationIcon = {
            IconButton(onClick = close) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
        })
    }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 600.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (onProfiles != null) OutlinedCard(onClick = onProfiles, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.profiles), Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium)
                }
                SettingsGroup(R.string.material_display, expanded == R.string.material_display, { expand(R.string.material_display) }) {
                    Choice(stringResource(R.string.design_app_theme), listOf(R.string.system, R.string.day, R.string.night), mode) {
                        prefs.edit().putInt("appTheme", it).apply()
                    }
                    Choice(stringResource(R.string.accent), Accent.entries.map { it.label }, accent.ordinal) { setAccent(Accent.entries[it]) }
                    ToggleRow(R.string.design_cover, adaptive) { prefs.edit().putBoolean("coverAccent", it).apply() }
                }
                SettingsGroup(R.string.design_reading, expanded == R.string.design_reading, { expand(R.string.design_reading) }) {
                    ReaderStyleEditor(prefs)
                }
                SettingsGroup(R.string.design_playback, expanded == R.string.design_playback, { expand(R.string.design_playback) }) {
                    val options = listOf(10, 15, 30, 60)
                    Choice(stringResource(R.string.design_skip), listOf(R.string.design_10s, R.string.design_15s,
                        R.string.design_30s, R.string.design_60s), options.indexOf(skip).takeIf { it >= 0 } ?: 1) {
                        prefs.edit().putInt("skipSeconds", options[it]).apply()
                    }
                }
                SettingsGroup(R.string.language, expanded == R.string.language, { expand(R.string.language) }) {
                    val options = listOf("", "de", "en")
                    Choice(stringResource(R.string.language), listOf(R.string.system, R.string.german, R.string.english),
                        options.indexOf(language).coerceAtLeast(0)) {
                        if (options[it] != language) {
                            display.edit().putString("language", options[it]).apply()
                            context.activity()?.recreate()
                        }
                    }
                }
                SettingsGroup(R.string.updates, expanded == R.string.updates, { expand(R.string.updates) }) {
                    Text(stringResource(R.string.current_version, updates.installed))
                    val last = if (updates.lastCheck > 0) DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                        .format(Date(updates.lastCheck)) else stringResource(R.string.design_never)
                    Text(stringResource(R.string.design_last_check, last), style = MaterialTheme.typography.bodyMedium)
                    ToggleRow(R.string.design_auto_updates, updates.automaticEnabled, updates::configureAutomatic)
                    Button(enabled = !updates.checking, onClick = { updates.check() }) { Text(stringResource(R.string.check_updates)) }
                    if (updates.checking) LinearProgressIndicator(Modifier.fillMaxWidth())
                    Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                        updates.available?.let { release ->
                            Text(stringResource(R.string.release_available, release.version))
                            OutlinedButton(onClick = { open(release.link) }) { Text(stringResource(R.string.open_update)) }
                        }
                        updates.status?.let { Text(stringResource(it)) }
                        if (browserFailed) Text(stringResource(R.string.update_error))
                    }
                    TextButton(onClick = { open("$RELEASES/latest") }) { Text(stringResource(R.string.open_releases)) }
                    if (updates.configured || updates.status == R.string.design_release_missing || updates.status == R.string.update_access) {
                        TextButton(enabled = !updates.checking, onClick = { accessDialog = true }) {
                            Text(stringResource(R.string.design_private_access))
                        }
                    }
                }
            }
        }
    }
    if (accessDialog) AlertDialog(onDismissRequest = { accessDialog = false; input = "" },
        title = { Text(stringResource(R.string.design_private_access)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.github_token_hint))
                OutlinedTextField(input, { input = it }, Modifier.fillMaxWidth(), singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), label = { Text(stringResource(R.string.github_token)) })
                if (updates.configured) TextButton(onClick = { updates.clearAccess(); accessDialog = false; input = "" }) {
                    Text(stringResource(R.string.update_token_remove))
                }
            }
        }, confirmButton = {
            TextButton(enabled = input.isNotBlank(), onClick = { updates.check(input.trim()); accessDialog = false; input = "" }) {
                Text(stringResource(R.string.update_token_save))
            }
        }, dismissButton = {
            TextButton(onClick = { accessDialog = false; input = "" }) { Text(stringResource(R.string.cancel)) }
        })
}
