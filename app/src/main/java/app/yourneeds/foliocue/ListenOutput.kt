package app.yourneeds.foliocue

import android.content.Context
import android.content.Intent
import android.media.MediaRouter
import android.media.MediaRouter2
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.math.roundToInt

@Composable
internal fun ListenOutputButton() {
    var opened by remember { mutableStateOf(false) }
    val device = AudilegePlayback.outputName
    val label = if (device == null) stringResource(R.string.route_title) else stringResource(R.string.route_current, device)
    IconButton(onClick = { opened = true }) {
        Icon(painterResource(R.drawable.audio_output), label,
            tint = if (device == null) LocalContentColor.current else MaterialTheme.colorScheme.primary)
    }
    if (opened) ListenOutputChooser { opened = false }
}

@Composable
internal fun ListenOutputStatus() {
    val player = AudilegePlayback
    val message = player.outputError?.let { stringResource(it) }
        ?: player.connectingOutput?.let { stringResource(R.string.route_connecting, it) }
        ?: player.outputName?.let { stringResource(R.string.route_airplay_device, it) }
    if (message != null) Text(message, style = MaterialTheme.typography.labelMedium,
        color = if (player.outputError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
}

@Composable
private fun activeSystemRoute(context: Context): String? {
    val router = remember(context) { context.getSystemService(MediaRouter::class.java) }
    fun current() = router?.getSelectedRoute(MediaRouter.ROUTE_TYPE_LIVE_AUDIO)?.getName(context)?.toString()
    var label by remember(router) { mutableStateOf(current()) }
    DisposableEffect(router) {
        val callback = object : MediaRouter.SimpleCallback() {
            override fun onRouteSelected(router: MediaRouter, type: Int, route: MediaRouter.RouteInfo) { label = current() }
            override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) { label = current() }
        }
        router?.addCallback(MediaRouter.ROUTE_TYPE_LIVE_AUDIO, callback)
        onDispose { router?.removeCallback(callback) }
    }
    return label
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun ListenOutputChooser(dismiss: () -> Unit) {
    val player = AudilegePlayback
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val discovery = remember(context) { AirPlayDiscovery.shared(context) }
    val owner = remember { Any() }
    val supported by produceState<Boolean?>(null) {
        value = withContext(Dispatchers.IO) { AirPlaySender.supported }
    }
    var attempt by remember { mutableIntStateOf(0) }
    var searched by remember { mutableStateOf(false) }
    var notices by remember { mutableStateOf(false) }
    var switcherUnavailable by remember { mutableStateOf(false) }
    var systemUnavailable by remember { mutableStateOf(false) }
    val systemRoute = activeSystemRoute(context)
    val activeId = player.outputId
    val activeName = player.outputName
    val connecting = player.connectingOutput
    val choices = discovery.speakers

    DisposableEffect(discovery, supported, lifecycle, notices) {
        if (supported == true && !notices) {
            fun update() {
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) discovery.acquire(owner)
                else discovery.release(owner)
            }
            val observer = LifecycleEventObserver { _, _ -> update() }
            lifecycle.addObserver(observer)
            update()
            onDispose { lifecycle.removeObserver(observer); discovery.release(owner) }
        } else onDispose { }
    }
    LaunchedEffect(attempt, supported, notices) {
        searched = false
        if (supported == true && !notices) { delay(2500); searched = true }
    }
    if (notices) {
        AirPlayNotices { notices = false }
        return
    }
    ModalBottomSheet(onDismissRequest = dismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.route_title), modifier = Modifier.weight(1f).semantics { heading() },
                    style = MaterialTheme.typography.headlineSmall)
                TextButton(onClick = dismiss) { Text(stringResource(R.string.close)) }
            }
            Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Column(Modifier.selectableGroup()) {
                    RouteOption(systemRoute ?: stringResource(R.string.route_this_device), stringResource(R.string.route_system),
                        activeId == null && connecting == null) { player.selectOutput(null) }
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Text(stringResource(R.string.route_airplay), style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.semantics { heading() })
                    if (activeId != null && activeName != null && choices.none { it.id == activeId }) {
                        RouteOption(activeName, null, true) { }
                    }
                    choices.forEach { speaker ->
                        key(speaker.id) {
                            RouteOption(speaker.name, null, speaker.id == activeId, enabled = connecting == null) {
                                if (activeId != speaker.id) player.selectOutput(speaker)
                            }
                        }
                    }
                }
                if (activeName != null) SpeakerVolume(activeName)
                val discoveryMessage = when {
                    supported == false -> R.string.route_unavailable
                    discovery.failed -> R.string.route_search_failed
                    choices.isNotEmpty() || activeName != null -> null
                    searched -> R.string.route_empty
                    else -> R.string.route_searching
                }
                if (discoveryMessage != null) Text(stringResource(discoveryMessage), style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 4.dp).semantics { liveRegion = LiveRegionMode.Polite })
                if (connecting != null) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(stringResource(R.string.route_connecting, connecting),
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                }
                player.outputError?.let {
                    Text(stringResource(it), color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                }
                if (discovery.failed || (searched && choices.isEmpty() && supported == true)) {
                    TextButton(onClick = { discovery.retry(); attempt++ }) { Text(stringResource(R.string.route_rescan)) }
                }
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                if (switcherUnavailable) Text(stringResource(R.string.route_switcher_unavailable),
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                TextButton(onClick = {
                    if (Build.VERSION.SDK_INT >= 34 && !switcherUnavailable) {
                        switcherUnavailable = !runCatching {
                            MediaRouter2.getInstance(context).showSystemOutputSwitcher()
                        }.getOrDefault(false)
                    } else {
                        systemUnavailable = runCatching {
                            context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                        }.isFailure
                    }
                }) {
                    Text(stringResource(if (Build.VERSION.SDK_INT >= 34 && !switcherUnavailable)
                        R.string.route_system_picker else R.string.route_bluetooth_settings))
                }
                if (systemUnavailable) Text(stringResource(R.string.route_system_unavailable), color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { notices = true }) { Text(stringResource(R.string.route_licenses)) }
            }
        }
    }
}

@Composable
private fun RouteOption(name: String, detail: String?, selected: Boolean, enabled: Boolean = true, choose: () -> Unit) {
    Row(Modifier.fillMaxWidth().selectable(selected, enabled, Role.RadioButton, choose).heightIn(min = 52.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Column {
            Text(name, style = MaterialTheme.typography.bodyLarge)
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SpeakerVolume(name: String) {
    val player = AudilegePlayback
    var pending by remember(name) { mutableStateOf<Int?>(null) }
    val volume = pending ?: player.outputVolume
    val label = stringResource(R.string.route_volume, name)
    Text(stringResource(R.string.route_volume_value, volume), style = MaterialTheme.typography.labelMedium)
    Slider(value = volume.toFloat(), onValueChange = { pending = it.roundToInt() },
        onValueChangeFinished = { pending?.let(player::changeOutputVolume); pending = null }, valueRange = 0f..100f,
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = label })
}

@Composable
private fun AirPlayNotices(back: () -> Unit) {
    val assets = LocalContext.current.assets
    val directory = "airplay-licenses"
    val names by produceState<List<String>?>(null, assets) {
        value = withContext(Dispatchers.IO) {
            try { assets.list(directory).orEmpty().sorted() } catch (_: IOException) { emptyList() }
        }
    }
    var selected by remember { mutableStateOf<String?>(null) }
    val filename = selected
    val returnToPrevious = { if (selected == null) back() else selected = null }
    AlertDialog(onDismissRequest = returnToPrevious,
        title = { Text(filename?.removeSuffix(".txt") ?: stringResource(R.string.route_licenses)) },
        text = {
            if (filename == null) {
                val files = names
                if (files == null) LinearProgressIndicator()
                else if (files.isEmpty()) Text(stringResource(R.string.route_licenses_unavailable))
                else LazyColumn { items(files) { name ->
                    TextButton(onClick = { selected = name }) { Text(name.removeSuffix(".txt")) }
                } }
            } else key(filename) {
                val paragraphs by produceState<List<String>?>(null, assets) {
                    value = withContext(Dispatchers.IO) {
                        try { assets.open("$directory/$filename").bufferedReader().use {
                            it.readText().replace("\r\n", "\n").split("\n\n")
                        } } catch (_: IOException) { emptyList() }
                    }
                }
                val content = paragraphs
                if (content == null) LinearProgressIndicator()
                else if (content.isEmpty()) Text(stringResource(R.string.route_licenses_unavailable))
                else LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(content) { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }, confirmButton = { TextButton(onClick = returnToPrevious) { Text(stringResource(R.string.back)) } })
}
