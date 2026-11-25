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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.text.DateFormat
import java.util.Date

internal const val RELEASES = "https://github.com/yourneeds-app/audilege/releases"
internal data class Release(val version: String, val link: String, val size: Long = -1, val sha256: String? = null)
private class ReleaseHttpError(val status: Int) : Exception()
private const val AUTO_INTERVAL = 6 * 60 * 60 * 1000L
private val versionPattern = Regex("""\d+\.\d+\.\d+""")

internal fun parseRelease(response: JSONObject): Release {
    val tag = response.getString("tag_name")
    val version = tag.removePrefix("v")
    require(version.matches(versionPattern) && !response.optBoolean("draft") && !response.optBoolean("prerelease"))
    val assets = response.getJSONArray("assets")
    val byName = (0 until assets.length()).mapNotNull { assets.optJSONObject(it) }.associateBy { it.optString("name") }
    val file = listOf("Audilege-$version.apk", "Audilege-$version-release.apk", "Audilege-$version-debug.apk").first { it in byName }
    val asset = byName.getValue(file)
    val checksum = if (asset.isNull("digest")) null else {
        val value = asset.getString("digest")
        require(value.matches(Regex("""sha256:[0-9a-fA-F]{64}""")))
        value.substringAfter(':').lowercase()
    }
    return Release(version, "$RELEASES/download/$tag/$file", asset.optLong("size", -1), checksum)
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
    var downloading by mutableStateOf(false)
        private set
    var progress by mutableStateOf<Int?>(null)
        private set
    var readyApk by mutableStateOf<File?>(null)
        private set

    init {
        val version = prefs.getString("updateVersion", null)
        val link = prefs.getString("updateLink", null)
        if (version != null && link != null && newerVersion(version, installed) && link.startsWith("$RELEASES/")) {
            available = Release(version, link, prefs.getLong("updateSize", -1), prefs.getString("updateSha256", null))
            if (link == prefs.getString("updateReadyFor", null)) {
                readyApk = prefs.getString("updateReadyPath", null)?.let(::File)?.takeIf { it.isFile }
            }
        }
    }

    fun configureAutomatic(enabled: Boolean) {
        automaticEnabled = enabled
        prefs.edit().putBoolean("updateAuto", enabled).apply()
        if (enabled) check(automatic = true)
    }

    fun check(candidate: String = token, automatic: Boolean = false) {
        if (checking || downloading) return
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
                val next = release.takeIf { newerVersion(it.version, installed) }
                if (available != next) clearPrepared()
                available = next
                lastCheck = System.currentTimeMillis()
                prefs.edit().putLong("updateLastSuccess", lastCheck).putString("updateVersion", release.version)
                    .putString("updateLink", release.link).putLong("updateSize", release.size)
                    .putString("updateSha256", release.sha256).apply()
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

    private fun clearPrepared() {
        readyApk = null
        prefs.edit().remove("updateReadyFor").remove("updateReadyPath").apply()
    }

    fun prepare(context: Context, release: Release) {
        if (checking || downloading || release != available) return
        val app = context.applicationContext
        downloading = true
        progress = null
        status = null
        scope.launch {
            try {
                val apk = UpdateInstaller.download(app, release) { progress = it }
                readyApk = apk
                prefs.edit().putString("updateReadyFor", release.link).putString("updateReadyPath", apk.absolutePath).apply()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                clearPrepared()
                status = R.string.update_download_failed
            } finally { downloading = false }
        }
    }

    fun openInstaller(context: Context, apk: File) {
        if (!apk.isFile) {
            clearPrepared()
            status = R.string.update_file_missing
            return
        }
        try {
            UpdateInstaller.install(context, apk)
            status = null
        } catch (_: Exception) {
            status = R.string.update_install_failed
        }
    }

    fun requestInstallPermission(context: Context): Boolean {
        return try {
            UpdateInstaller.requestPermission(context)
            status = null
            true
        } catch (_: Exception) {
            status = R.string.update_install_failed
            false
        }
    }

    fun clearAccess() {
        if (checking || downloading) return
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
    OutlinedCard(Modifier.fillMaxWidth(), shape = AudilegeLayout.cardShape) {
        Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = choose).semantics { stateDescription = state }.heightIn(min = 56.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(title), Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleMedium)
            Icon(if (selected) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
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

@Composable
private fun AvailableUpdate(updates: AppUpdates, release: Release) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var resumed by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    var allowed by remember(context) { mutableStateOf(UpdateInstaller.canInstall(context)) }
    var installAfterDownload by remember(release) { mutableStateOf(false) }
    var awaitingPermission by rememberSaveable(release.link) { mutableStateOf(false) }
    var leftForPermission by rememberSaveable(release.link) { mutableStateOf(false) }
    fun prepareToInstall() {
        if (updates.checking || updates.downloading) return
        installAfterDownload = true
        updates.prepare(context, release)
    }
    DisposableEffect(lifecycle, context, release) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                allowed = UpdateInstaller.canInstall(context)
                resumed = true
                if (awaitingPermission && leftForPermission) {
                    awaitingPermission = false
                    leftForPermission = false
                    if (allowed) prepareToInstall()
                }
            } else if (event == Lifecycle.Event.ON_PAUSE) {
                resumed = false
                if (awaitingPermission) leftForPermission = true
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val apk = updates.readyApk
    LaunchedEffect(installAfterDownload, updates.downloading, resumed, apk) {
        if (resumed && installAfterDownload && !updates.downloading) {
            installAfterDownload = false
            if (apk != null && allowed) updates.openInstaller(context, apk)
        }
    }
    Text(stringResource(R.string.release_available, release.version))
    when {
        updates.downloading -> {
            val progress = updates.progress
            if (progress == null) {
                Text(stringResource(R.string.update_downloading))
                LinearProgressIndicator(Modifier.fillMaxWidth())
            } else {
                Text(stringResource(R.string.update_download_progress, progress))
                LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
            }
        }
        apk == null -> Button(enabled = !updates.checking, onClick = ::prepareToInstall) { Text(stringResource(R.string.update_download)) }
        !allowed -> {
            Text(stringResource(R.string.update_source_permission))
            Button(enabled = !updates.checking, onClick = {
                awaitingPermission = true
                leftForPermission = false
                if (!updates.requestInstallPermission(context)) awaitingPermission = false
            }) { Text(stringResource(R.string.update_allow_install)) }
        }
        else -> Button(enabled = !updates.checking, onClick = ::prepareToInstall) { Text(stringResource(R.string.update_install)) }
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
    var showNotices by rememberSaveable { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    var browserFailed by remember { mutableStateOf(false) }
    val mode = watchPreference(prefs, setOf("appTheme")) { prefs.getInt("appTheme", 0).coerceIn(0, 2) }
    val adaptive = watchPreference(prefs, setOf("coverAccent")) { prefs.getBoolean("coverAccent", true) }
    val skip = watchPreference(prefs, setOf("skipSeconds")) { prefs.getInt("skipSeconds", 15) }
    val resumeRewind = watchPreference(prefs, setOf("resumeRewindSeconds")) { prefs.getInt("resumeRewindSeconds", 0) }
    val podcastDownloads = watchPreference(display, setOf(PodcastDownloadJob.ENABLED)) {
        display.getBoolean(PodcastDownloadJob.ENABLED, false)
    }
    val language = watchPreference(display, setOf("language")) { display.getString("language", "").orEmpty() }
    fun open(url: String) {
        browserFailed = runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }.isFailure
    }
    fun expand(id: Int) { expanded = if (expanded == id) 0 else id }
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.safeDrawingPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
            FolioBar(stringResource(R.string.app_settings), back = close)
            Column(Modifier.weight(1f).widthIn(max = AudilegeLayout.contentWidth).fillMaxWidth()
                .verticalScroll(rememberScrollState()).padding(horizontal = AudilegeLayout.pageInset, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (onProfiles != null) OutlinedCard(onClick = onProfiles, modifier = Modifier.fillMaxWidth(),
                    shape = AudilegeLayout.cardShape) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.profile_and_server), Modifier.weight(1f),
                            style = MaterialTheme.typography.titleMedium)
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
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
                    val rewindOptions = listOf(0, 5, 10, 15, 30)
                    Choice(
                        stringResource(R.string.design_resume_rewind),
                        listOf(R.string.audio_off, R.string.design_5s, R.string.design_10s, R.string.design_15s, R.string.design_30s),
                        rewindOptions.indexOf(resumeRewind).coerceAtLeast(0),
                    ) { prefs.edit().putInt("resumeRewindSeconds", rewindOptions[it]).apply() }
                    AudiobookEqualizerSetting(prefs)
                }
                SettingsGroup(R.string.shelf_podcasts, expanded == R.string.shelf_podcasts, { expand(R.string.shelf_podcasts) }) {
                    ToggleRow(R.string.podcast_wifi_download, podcastDownloads) { enabled ->
                        display.edit().putBoolean(PodcastDownloadJob.ENABLED, enabled).apply()
                        PodcastDownloadJob.schedule(context, enabled, checkNow = enabled)
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
                SettingsGroup(R.string.info_licenses, expanded == R.string.info_licenses, { expand(R.string.info_licenses) }) {
                    TextButton(onClick = { showNotices = true }) { Text(stringResource(R.string.route_licenses)) }
                    TextButton(onClick = { open(RELEASES.substringBefore("/releases")) }) { Text("GitHub") }
                    TextButton(onClick = { open("$RELEASES/tag/v${updates.installed}") }) {
                        Text(stringResource(R.string.current_version, updates.installed))
                    }
                }
                SettingsGroup(R.string.updates, expanded == R.string.updates, { expand(R.string.updates) }) {
                    Text(stringResource(R.string.current_version, updates.installed))
                    val last = if (updates.lastCheck > 0) DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                        .format(Date(updates.lastCheck)) else stringResource(R.string.design_never)
                    Text(stringResource(R.string.design_last_check, last), style = MaterialTheme.typography.bodyMedium)
                    ToggleRow(R.string.design_auto_updates, updates.automaticEnabled, updates::configureAutomatic)
                    OutlinedButton(enabled = !updates.checking && !updates.downloading, onClick = { updates.check() }) {
                        Text(stringResource(R.string.check_updates))
                    }
                    if (updates.checking) LinearProgressIndicator(Modifier.fillMaxWidth())
                    updates.available?.let { release ->
                        AvailableUpdate(updates, release)
                        if (updates.status == R.string.update_download_failed || updates.status == R.string.update_install_failed) {
                            TextButton(onClick = { open(release.link) }) { Text(stringResource(R.string.open_update)) }
                        }
                    }
                    Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                        updates.status?.let { Text(stringResource(it)) }
                        if (browserFailed) Text(stringResource(R.string.update_error))
                    }
                    TextButton(onClick = { open("$RELEASES/latest") }) { Text(stringResource(R.string.open_releases)) }
                    if (updates.configured || updates.status == R.string.design_release_missing || updates.status == R.string.update_access) {
                        TextButton(enabled = !updates.checking && !updates.downloading, onClick = { accessDialog = true }) {
                            Text(stringResource(R.string.design_private_access))
                        }
                    }
                }
            }
        }
    }
    if (showNotices) AirPlayNotices { showNotices = false }
    if (accessDialog) AlertDialog(onDismissRequest = { accessDialog = false; input = "" },
        title = { Text(stringResource(R.string.design_private_access)) },
        text = {
            AppVolumeFeedback()
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
