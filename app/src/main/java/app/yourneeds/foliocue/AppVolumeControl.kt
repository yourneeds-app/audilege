package app.yourneeds.foliocue

import android.view.KeyEvent
import androidx.compose.runtime.*
import kotlin.math.roundToInt

internal enum class VolumeCommand { UP, DOWN, MUTE }
internal data class AppVolume(val key: String, val name: String, val level: Int, val maximum: Int,
                              val fixed: Boolean = false) {
    val percent: Int get() = if (maximum <= 0) 0 else (100.0 * level / maximum).roundToInt().coerceIn(0, 100)
}
internal interface AppVolumeBackend {
    fun read(): AppVolume
    fun adjust(target: AppVolume, command: VolumeCommand)
    fun set(target: AppVolume, percent: Int)
}

/** Own only matching foreground volume events; never claim media, navigation or background keys. */
internal class AppVolumeControl(private val backend: AppVolumeBackend) {
    private val held = mutableSetOf<Int>()
    var resumed = false
        private set
    var feedback by mutableStateOf<AppVolume?>(null)
        private set
    var revision by mutableIntStateOf(0)
        private set

    fun resume() { resumed = true }
    fun pause() { resumed = false; held.clear(); dismiss() }
    fun dismiss() { feedback = null }
    fun read(): AppVolume? = runCatching { backend.read() }.getOrNull()
    fun refresh() {
        val old = feedback ?: return
        val current = read()
        feedback = current?.takeIf { it.key == old.key }
    }
    private fun changed(expected: String? = null, operation: (AppVolume) -> Unit): Boolean {
        val target = read() ?: return false
        if (expected != null && target.key != expected) return false
        if (!target.fixed && runCatching { operation(target) }.isFailure) return false
        // An unavailable read after a successful write must not cause Android to adjust a second time.
        val current = read()
        if (resumed && current?.key == target.key) { feedback = current; revision++ }
        else feedback = null
        return true
    }

    fun set(percent: Int, expected: String? = null) = changed(expected) { backend.set(it, percent.coerceIn(0, 100)) }

    fun dispatch(key: Int, action: Int, repeat: Int, focused: Boolean): Boolean {
        if (action == KeyEvent.ACTION_UP && held.remove(key)) return true
        if (!resumed || !focused) return false
        val command = when (key) {
            KeyEvent.KEYCODE_VOLUME_UP -> VolumeCommand.UP
            KeyEvent.KEYCODE_VOLUME_DOWN -> VolumeCommand.DOWN
            KeyEvent.KEYCODE_VOLUME_MUTE -> VolumeCommand.MUTE
            else -> return false
        }
        if (action != KeyEvent.ACTION_DOWN) return false
        if (command == VolumeCommand.MUTE && repeat > 0 && key in held) return true
        val handled = changed { backend.adjust(it, command) }
        if (handled) held.add(key)
        return handled
    }
}
