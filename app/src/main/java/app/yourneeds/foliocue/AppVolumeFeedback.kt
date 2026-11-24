package app.yourneeds.foliocue

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

internal tailrec fun Context.volumeActivity(): MainActivity? = when (this) {
    is MainActivity -> this
    is ContextWrapper -> baseContext.volumeActivity()
    else -> null
}

/** Focusable Compose menus own a popup rather than an Activity/Dialog window. */
@Composable
internal fun Modifier.appVolumeKeys(): Modifier {
    val control = LocalContext.current.volumeActivity()?.appVolume ?: return this
    return onPreviewKeyEvent { event -> event.nativeKeyEvent.let {
        control.dispatch(it.keyCode, it.action, it.repeatCount, focused = true)
    } }
}

internal object AppVolumePosition : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection,
                                   popupContentSize: IntSize): IntOffset =
        IntOffset(((windowSize.width - popupContentSize.width) / 2).coerceAtLeast(0),
            ((windowSize.height - popupContentSize.height) / 2).coerceAtLeast(0))
}

/** An app-owned window only: no draw-over-other-apps permission or background overlay. */
@Composable
internal fun AppVolumeFeedback() {
    val view = LocalView.current
    val activity = LocalContext.current.volumeActivity() ?: return
    val control = activity.appVolume
    var focused by remember(view) { mutableStateOf(view.hasWindowFocus()) }
    DisposableEffect(view) {
        val listener = android.view.ViewTreeObserver.OnWindowFocusChangeListener { focused = it }
        view.viewTreeObserver.addOnWindowFocusChangeListener(listener)
        onDispose { if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnWindowFocusChangeListener(listener) }
    }
    // Compose sheets/dialogs have their own window, which otherwise handles volume before the activity.
    val dialogWindow = (view.parent as? DialogWindowProvider)?.window
    DisposableEffect(dialogWindow, control) {
        if (dialogWindow == null) return@DisposableEffect onDispose { }
        val release = installVolumeKeys(dialogWindow, control)
        onDispose { release() }
    }
    val snapshot = control.feedback ?: return
    if (!focused || !control.resumed) return
    val revision = control.revision
    val accessibility = androidx.compose.ui.platform.LocalAccessibilityManager.current
    LaunchedEffect(control, revision) {
        val timeout = accessibility?.calculateRecommendedTimeoutMillis(2000, containsIcons = true, containsText = true) ?: 2000
        delay(timeout.coerceAtLeast(2000))
        control.dismiss()
    }
    LaunchedEffect(control, snapshot.key) {
        while (true) { delay(150); control.refresh() }
    }
    // The host may be a zero-size effect or a dialog text slot. Center in the window, not that slot.
    Popup(popupPositionProvider = AppVolumePosition, properties = PopupProperties(focusable = false,
        dismissOnBackPress = false, dismissOnClickOutside = false)) {
        AppVolumeCard(snapshot)
    }
}

@Composable
internal fun AppVolumeCard(snapshot: AppVolume) {
    val percent = stringResource(R.string.app_volume_percent, snapshot.percent)
    val state = if (snapshot.fixed) stringResource(R.string.app_volume_fixed) else percent
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.inverseSurface,
            contentColor = MaterialTheme.colorScheme.inverseOnSurface, shadowElevation = 6.dp,
            modifier = Modifier.padding(24.dp).widthIn(max = 260.dp).fillMaxWidth()
                .clearAndSetSemantics { liveRegion = LiveRegionMode.Polite; contentDescription = "${snapshot.name}, $state" }) {
            Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(painterResource(if (snapshot.percent == 0) R.drawable.audio_volume_off else R.drawable.audio_volume),
                    null, Modifier.size(32.dp))
                Text(snapshot.name, style = MaterialTheme.typography.titleMedium, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                LinearProgressIndicator(progress = { snapshot.percent / 100f }, modifier = Modifier.fillMaxWidth().height(5.dp),
                    color = MaterialTheme.colorScheme.inversePrimary, trackColor = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = .2f))
                Text(state, style = MaterialTheme.typography.labelLarge)
            }
        }
}

@Composable
internal fun AppOutputVolume() {
    val activity = LocalContext.current.volumeActivity() ?: return
    val control = activity.appVolume
    var value by remember { mutableStateOf(control.read()) }
    var pending by remember { mutableStateOf<Pair<String, Float>?>(null) }
    var error by remember { mutableStateOf(false) }
    LaunchedEffect(control) { while (true) { value = control.read(); delay(200) } }
    val target = value ?: return
    LaunchedEffect(target.key) { pending = null; error = false }
    val percent = pending?.takeIf { it.first == target.key }?.second?.roundToInt() ?: target.percent
    val label = stringResource(R.string.route_volume, target.name)
    Text(stringResource(R.string.app_volume_value, percent), style = MaterialTheme.typography.labelMedium)
    Slider(value = percent.toFloat(), onValueChange = { pending = (pending?.first ?: target.key) to it },
        onValueChangeFinished = {
            pending?.let { error = !control.set(it.second.roundToInt(), it.first) }
            pending = null
            value = control.read()
        }, valueRange = 0f..100f, enabled = !target.fixed,
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = label })
    if (target.fixed) Text(stringResource(R.string.app_volume_fixed), style = MaterialTheme.typography.bodySmall)
    if (error) Text(stringResource(R.string.app_volume_failed), color = MaterialTheme.colorScheme.error)
}
