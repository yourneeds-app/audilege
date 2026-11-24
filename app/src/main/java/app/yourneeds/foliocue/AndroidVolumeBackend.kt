package app.yourneeds.foliocue

import android.content.Context
import android.media.AudioManager
import android.media.MediaRouter
import android.view.KeyEvent
import android.view.Window
import kotlin.math.roundToInt

/** Public Window.Callback boundary, before either Activity, WebView or media-session defaults. */
internal fun installVolumeKeys(window: Window, control: AppVolumeControl): () -> Unit {
    val original = window.callback ?: return { }
    val wrapper = object : Window.Callback by original {
        override fun dispatchKeyEvent(event: KeyEvent): Boolean =
            control.dispatch(event.keyCode, event.action, event.repeatCount, window.decorView.hasWindowFocus()) ||
                original.dispatchKeyEvent(event)
    }
    window.callback = wrapper
    return { if (window.callback === wrapper) window.callback = original }
}

internal class AndroidVolumeBackend(private val context: Context,
    private val remoteRead: () -> AppVolume? = {
        AudilegePlayback.outputId?.let { id -> AppVolume("airplay:$id",
            AudilegePlayback.outputName ?: context.getString(R.string.route_airplay), AudilegePlayback.outputVolume, 100) }
    },
    private val remoteSet: (Int) -> Unit = AudilegePlayback::changeOutputVolume,
) : AppVolumeBackend {
    private val manager get() = context.getSystemService(AudioManager::class.java)
    private var mutedSpeaker: String? = null
    private var beforeMute = 25

    override fun read(): AppVolume {
        remoteRead()?.let { return it }
        val audio = manager
        val route = context.getSystemService(MediaRouter::class.java)?.getSelectedRoute(MediaRouter.ROUTE_TYPE_LIVE_AUDIO)
        val local = route?.takeIf { it.playbackType == MediaRouter.RouteInfo.PLAYBACK_TYPE_LOCAL }
        val name = local?.getName(context)?.toString()?.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.route_this_device)
        return AppVolume("local:${local?.name}", name,
            if (audio.isStreamMute(AudioManager.STREAM_MUSIC)) 0 else audio.getStreamVolume(AudioManager.STREAM_MUSIC),
            audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC), audio.isVolumeFixed)
    }

    override fun adjust(target: AppVolume, command: VolumeCommand) {
        if (read().key != target.key) return
        if (target.key.startsWith("airplay:")) {
            if (mutedSpeaker != target.key) { mutedSpeaker = target.key; beforeMute = 25 }
            val next = when (command) {
                VolumeCommand.UP -> target.level + 5
                VolumeCommand.DOWN -> target.level - 5
                VolumeCommand.MUTE -> if (target.level == 0) beforeMute else 0
            }
            if (next <= 0 && target.level > 0) beforeMute = target.level
            remoteSet(next.coerceIn(0, 100))
        } else {
            // Deliberately omit FLAG_SHOW_UI: our foreground card is the only feedback.
            manager.adjustStreamVolume(AudioManager.STREAM_MUSIC, when (command) {
                VolumeCommand.UP -> AudioManager.ADJUST_RAISE
                VolumeCommand.DOWN -> AudioManager.ADJUST_LOWER
                VolumeCommand.MUTE -> AudioManager.ADJUST_TOGGLE_MUTE
            }, 0)
        }
    }

    override fun set(target: AppVolume, percent: Int) {
        if (read().key != target.key) return
        if (target.key.startsWith("airplay:")) remoteSet(percent)
        else manager.setStreamVolume(AudioManager.STREAM_MUSIC,
            (percent * target.maximum / 100.0).roundToInt().coerceIn(
                manager.getStreamMinVolume(AudioManager.STREAM_MUSIC), target.maximum), 0)
    }
}
