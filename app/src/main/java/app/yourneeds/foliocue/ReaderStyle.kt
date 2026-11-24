package app.yourneeds.foliocue

import android.content.SharedPreferences
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import kotlin.math.roundToInt

internal fun SharedPreferences.paper(default: Paper = Paper.Day) =
    Paper.entries.getOrElse(getInt("paper", default.ordinal)) { default }

internal data class ReaderStyle(
    val paper: Paper, val followsSystem: Boolean, val font: Typeface, val size: Int, val margin: Int,
    val lineSpacing: Float, val paged: Boolean, val underline: Boolean, val hue: Int, val vivid: Boolean,
) {
    private val night get() = paper == Paper.Night
    val highlight: Long get() = when (hue) {
        1 -> if (night && vivid) 0xFF78592A else if (night) 0xFF58442D else if (vivid) 0xFFF8CA79 else 0xFFF1E1BE
        2 -> if (night && vivid) 0xFF655581 else if (night) 0xFF484257 else if (vivid) 0xFFCDBDEE else 0xFFE5DFF0
        else -> if (night && vivid) 0xFF2A6560 else if (night) 0xFF304D4A else if (vivid) 0xFF98D6C9 else 0xFFDAE9DF
    }
    val highlightInk: Long get() = when (hue) {
        1 -> if (night) 0xFFFFE3AC else 0xFF573A10
        2 -> if (night) 0xFFF1E7FF else 0xFF49365F
        else -> if (night) 0xFFD4FFF5 else 0xFF124A44
    }
}

private val displayKeys = setOf("paper", "systemPaper", "font", "size", "pageMargin", "lineSpacing",
    "paged", "markUnderline", "markHue", "markVivid")

private fun SharedPreferences.readStyle(darkSystem: Boolean, defaultPaper: Paper): ReaderStyle {
    val follow = getBoolean("systemPaper", false)
    val paper = if (follow) { if (darkSystem) Paper.Night else Paper.Day } else paper(defaultPaper)
    return ReaderStyle(paper, follow, Typeface.entries.getOrElse(getInt("font", 0)) { Typeface.Original },
        getInt("size", 100).coerceIn(75, 200), getInt("pageMargin", 20).coerceIn(8, 56),
        getFloat("lineSpacing", 1.5f).coerceIn(1.15f, 2f), getBoolean("paged", false),
        getBoolean("markUnderline", false), getInt("markHue", 0).coerceIn(0, 2), getBoolean("markVivid", false))
}

@Composable
internal fun rememberReaderStyle(prefs: SharedPreferences, defaultPaper: Paper = Paper.Day): ReaderStyle {
    val dark = isSystemInDarkTheme()
    return key(dark, defaultPaper) { watchPreference(prefs, displayKeys) { prefs.readStyle(dark, defaultPaper) } }
}

private const val SAVED_STYLE = "readerStyleProfile"

private fun SharedPreferences.saveStyle(style: ReaderStyle) {
    val data = JSONObject()
        .put("paper", style.paper.ordinal).put("systemPaper", style.followsSystem).put("font", style.font.ordinal)
        .put("size", style.size).put("pageMargin", style.margin).put("lineSpacing", style.lineSpacing.toDouble())
        .put("paged", style.paged).put("markUnderline", style.underline).put("markHue", style.hue)
        .put("markVivid", style.vivid)
    edit().putString(SAVED_STYLE, data.toString()).apply()
}

private fun SharedPreferences.restoreStyle() {
    val data = runCatching { JSONObject(getString(SAVED_STYLE, "").orEmpty()) }.getOrNull() ?: return
    edit().putInt("paper", data.optInt("paper", 0)).putBoolean("systemPaper", data.optBoolean("systemPaper"))
        .putInt("font", data.optInt("font", 0)).putInt("size", data.optInt("size", 100))
        .putInt("pageMargin", data.optInt("pageMargin", 20)).putFloat("lineSpacing", data.optDouble("lineSpacing", 1.5).toFloat())
        .putBoolean("paged", data.optBoolean("paged")).putBoolean("markUnderline", data.optBoolean("markUnderline"))
        .putInt("markHue", data.optInt("markHue", 0)).putBoolean("markVivid", data.optBoolean("markVivid")).apply()
}

@Composable
private fun StyleSlider(label: Int, value: Int, unit: String, range: IntRange, step: Int, onValue: (Int) -> Unit) {
    val name = stringResource(label)
    Text("$name · $value$unit", style = MaterialTheme.typography.bodyMedium)
    Slider(value.toFloat(), { onValue((range.first + ((it - range.first) / step).roundToInt() * step).coerceIn(range)) },
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = name },
        valueRange = range.first.toFloat()..range.last.toFloat(), steps = (range.last - range.first) / step - 1)
}

@Composable
internal fun ReaderStyleEditor(prefs: SharedPreferences, defaultPaper: Paper = Paper.Day) {
    val style = rememberReaderStyle(prefs, defaultPaper)
    val hasSaved = watchPreference(prefs, setOf(SAVED_STYLE)) { prefs.contains(SAVED_STYLE) }
    var saved by remember(style) { mutableStateOf(false) }
    val sample = buildAnnotatedString {
        append(stringResource(R.string.design_sample_intro))
        append(' ')
        withStyle(SpanStyle(color = Color(style.highlightInk),
            background = if (style.underline) Color.Transparent else Color(style.highlight),
            textDecoration = if (style.underline) TextDecoration.Underline else TextDecoration.None)) {
            append(stringResource(R.string.design_sample_active))
        }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Surface(color = Color(style.paper.bg), shape = MaterialTheme.shapes.medium,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
            Text(sample, Modifier.fillMaxWidth().padding(horizontal = style.margin.dp, vertical = 16.dp),
                color = Color(style.paper.ink), fontSize = (17 * style.size / 100f).sp,
                lineHeight = (17 * style.size / 100f * style.lineSpacing).sp,
                fontFamily = when (style.font) {
                    Typeface.Sans -> FontFamily.SansSerif
                    Typeface.Mono -> FontFamily.Monospace
                    else -> FontFamily.Serif
                })
        }
        Choice(stringResource(R.string.theme), Paper.entries.map { it.label } + R.string.system,
            if (style.followsSystem) Paper.entries.size else style.paper.ordinal) { index ->
            val edit = prefs.edit().putBoolean("systemPaper", index == Paper.entries.size)
            Paper.entries.getOrNull(index)?.let { edit.putInt("paper", it.ordinal) }
            edit.apply()
        }
        Choice(stringResource(R.string.font), Typeface.entries.map { it.label }, style.font.ordinal) {
            prefs.edit().putInt("font", it).apply()
        }
        StyleSlider(R.string.size, style.size, " %", 75..200, 5) { prefs.edit().putInt("size", it).apply() }
        StyleSlider(R.string.design_spacing, (style.lineSpacing * 100).roundToInt(), " %", 115..200, 5) {
            prefs.edit().putFloat("lineSpacing", it / 100f).apply()
        }
        StyleSlider(R.string.design_margins, style.margin, " dp", 8..56, 4) {
            prefs.edit().putInt("pageMargin", it).apply()
        }
        Choice(stringResource(R.string.design_navigation), listOf(R.string.design_scroll, R.string.design_pages),
            if (style.paged) 1 else 0) { prefs.edit().putBoolean("paged", it == 1).apply() }
        Choice(stringResource(R.string.design_mark), listOf(R.string.design_fill, R.string.design_underline),
            if (style.underline) 1 else 0) { prefs.edit().putBoolean("markUnderline", it == 1).apply() }
        Choice(stringResource(R.string.design_mark_color), listOf(R.string.teal, R.string.design_amber, R.string.iris),
            style.hue) { prefs.edit().putInt("markHue", it).apply() }
        if (!style.underline) Choice(stringResource(R.string.design_strength), listOf(R.string.design_soft, R.string.design_vivid),
            if (style.vivid) 1 else 0) { prefs.edit().putBoolean("markVivid", it == 1).apply() }
        OutlinedButton(onClick = { prefs.saveStyle(style); saved = true }) {
            Text(stringResource(if (saved) R.string.design_profile_saved else R.string.design_profile_save))
        }
        if (hasSaved) TextButton(onClick = { prefs.restoreStyle() }) { Text(stringResource(R.string.design_profile_restore)) }
    }
}
