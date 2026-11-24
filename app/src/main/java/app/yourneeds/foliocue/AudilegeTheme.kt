package app.yourneeds.foliocue

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal enum class Accent(val label: Int, val dark: Long, val light: Long, val highlight: Long) {
    Teal(R.string.teal, 0xFF07575A, 0xFF91D9D5, 0xFFD8E8DF),
    Clay(R.string.clay, 0xFF854635, 0xFFEAB4A5, 0xFFF3DCCF),
    Iris(R.string.iris, 0xFF514C82, 0xFFCDC2F1, 0xFFE5DFF2),
    System(R.string.system, 0xFF07575A, 0xFF91D9D5, 0xFFD8E8DF),
}

@Composable
internal fun <T> watchPreference(prefs: SharedPreferences, keys: Set<String>, read: () -> T): T {
    val currentRead by rememberUpdatedState(read)
    var value by remember(prefs, keys) { mutableStateOf(read()) }
    DisposableEffect(prefs, keys) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key in keys) value = currentRead()
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        value = currentRead()
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return value
}

private fun neutralScheme(night: Boolean, accent: Color, surface: Color, ink: Color): ColorScheme {
    val base = if (night) darkColorScheme() else lightColorScheme()
    val variant = lerp(surface, ink, if (night) 0.095f else 0.065f)
    val subtleInk = lerp(ink, surface, if (night) 0.19f else 0.20f)
    val container = lerp(surface, accent, if (night) 0.19f else 0.12f)
    val onAccent = if (accent.luminance() > 0.179f) Color.Black else Color.White
    return base.copy(
        primary = accent, onPrimary = onAccent, primaryContainer = container, onPrimaryContainer = ink,
        secondary = accent, onSecondary = onAccent, secondaryContainer = container, onSecondaryContainer = ink,
        tertiary = accent, onTertiary = onAccent, tertiaryContainer = container, onTertiaryContainer = ink,
        background = surface, onBackground = ink, surface = surface, onSurface = ink,
        surfaceVariant = variant, onSurfaceVariant = subtleInk, surfaceTint = accent,
        surfaceContainerLowest = if (night) Color(0xFF111313) else Color.White,
        surfaceContainerLow = lerp(surface, ink, 0.025f), surfaceContainer = lerp(surface, ink, 0.045f),
        surfaceContainerHigh = variant, surfaceContainerHighest = lerp(surface, ink, 0.10f),
        outline = lerp(ink, surface, 0.43f), outlineVariant = lerp(surface, ink, 0.19f),
    )
}

@Composable
private fun chosenAccent(accent: Accent, night: Boolean): Color {
    val context = LocalContext.current
    return if (accent == Accent.System && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (night) dynamicDarkColorScheme(context).primary else dynamicLightColorScheme(context).primary
    } else Color(if (night) accent.light else accent.dark)
}

@Composable
internal fun appearanceColors(paper: Paper, accent: Accent): ColorScheme {
    val night = paper == Paper.Night
    return neutralScheme(night, chosenAccent(accent, night), Color(paper.bg), Color(paper.ink))
}

@Composable
internal fun appAppearanceColors(accent: Accent): ColorScheme {
    val prefs = LocalContext.current.getSharedPreferences("reader", Context.MODE_PRIVATE)
    val mode = watchPreference(prefs, setOf("appTheme")) { prefs.getInt("appTheme", 0) }
    val systemNight = isSystemInDarkTheme()
    val night = when (mode) { 1 -> false; 2 -> true; else -> systemNight }
    return neutralScheme(night, chosenAccent(accent, night),
        if (night) Color(0xFF191D1D) else Color(0xFFFCFBF9),
        if (night) Color(0xFFF2F1EE) else Color(0xFF202423))
}

/** Select a recognisable hue while ignoring transparent borders, type and incidental saturated pixels. */
private fun artworkColor(bitmap: Bitmap): Color? {
    val votes = DoubleArray(18)
    val sums = Array(18) { DoubleArray(3) }
    val hsv = FloatArray(3)
    val sample = Bitmap.createScaledBitmap(bitmap, 32, 32, true)
    try {
        val pixels = IntArray(32 * 32)
        sample.getPixels(pixels, 0, 32, 0, 0, 32, 32)
        for (pixel in pixels) {
            if (android.graphics.Color.alpha(pixel) < 180) continue
            android.graphics.Color.colorToHSV(pixel, hsv)
            if (hsv[1] < 0.19f || hsv[2] !in 0.17f..0.97f) continue
            val bucket = ((hsv[0] + 10f) / 20f).toInt() % votes.size
            val weight = (0.25 + hsv[1]) * (0.65 + hsv[2] * 0.35)
            votes[bucket] += weight
            sums[bucket][0] += android.graphics.Color.red(pixel) * weight
            sums[bucket][1] += android.graphics.Color.green(pixel) * weight
            sums[bucket][2] += android.graphics.Color.blue(pixel) * weight
        }
        val best = votes.indices.maxBy { votes[it] }
        if (votes[best] < 15.0) return null
        val channels = sums[best].map { (it / votes[best]).toInt().coerceIn(0, 255) }
        return Color(channels[0], channels[1], channels[2])
    } finally {
        if (sample !== bitmap) sample.recycle()
    }
}

@Composable
internal fun CoverTheme(covers: CoverStore, key: String, load: () -> InputStream?, content: @Composable () -> Unit) {
    val prefs = LocalContext.current.getSharedPreferences("reader", Context.MODE_PRIVATE)
    val enabled = watchPreference(prefs, setOf("coverAccent")) { prefs.getBoolean("coverAccent", true) }
    val currentLoad by rememberUpdatedState(load)
    val source by produceState<Color?>(null, enabled, covers, key) {
        value = null
        if (enabled) {
            val bitmap = covers.get(key, currentLoad)
            value = bitmap?.let { withContext(Dispatchers.Default) { artworkColor(it) } }
        }
    }
    val palette = MaterialTheme.colorScheme
    val imageColor = source
    val result = if (enabled && imageColor != null) {
        val night = palette.background.luminance() < 0.25f
        var accent = lerp(imageColor, if (night) Color.White else Color.Black, if (night) 0.55f else 0.22f)
        val target = if (night) Color.White else Color.Black
        while (if (night) accent.luminance() < 0.52f else accent.luminance() > 0.16f) {
            accent = lerp(accent, target, 0.12f)
        }
        neutralScheme(night, accent, lerp(palette.background, imageColor, if (night) 0.075f else 0.035f), palette.onBackground)
    } else palette
    MaterialTheme(colorScheme = result, content = content)
}
