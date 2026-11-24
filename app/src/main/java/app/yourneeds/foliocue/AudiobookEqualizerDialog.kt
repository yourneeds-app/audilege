package app.yourneeds.foliocue

import android.content.SharedPreferences
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

private data class TonePreset(val tone: AudiobookTone, val label: Int, val description: Int)

private val voicePresets = listOf(
    TonePreset(AudiobookTone.Off, R.string.audio_off, R.string.eq_original),
    TonePreset(AudiobookTone.Clear, R.string.eq_clear, R.string.eq_clear_detail),
    TonePreset(AudiobookTone.Warm, R.string.eq_warm, R.string.eq_warm_detail),
    TonePreset(AudiobookTone.Gentle, R.string.eq_gentle, R.string.eq_gentle_detail),
)

@Composable
internal fun AudiobookEqualizerSetting(prefs: SharedPreferences) {
    val tone = watchPreference(prefs, setOf(AudiobookTone.KEY)) { AudiobookTone.read(prefs) }
    val current = voicePresets.firstOrNull { it.tone == tone }?.label ?: R.string.eq_custom
    var show by rememberSaveable { mutableStateOf(false) }
    TextButton(onClick = { show = true }) {
        Text(stringResource(R.string.eq_title) + " · " + stringResource(current))
    }
    if (show) AudiobookEqualizerDialog(prefs) { show = false }
}

@Composable
internal fun AudiobookEqualizerDialog(prefs: SharedPreferences, dismiss: () -> Unit) {
    val tone = watchPreference(prefs, setOf(AudiobookTone.KEY)) { AudiobookTone.read(prefs) }
    val custom = voicePresets.none { it.tone == tone }
    var fineTune by rememberSaveable { mutableStateOf(custom) }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(stringResource(R.string.eq_title)) },
        confirmButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.close)) } },
        text = {
            AppVolumeFeedback()
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                if (AudilegePlayback.localEqualizerUnavailable) {
                    Text(stringResource(R.string.eq_unavailable),
                        Modifier.padding(bottom = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
                        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }
                Column(Modifier.selectableGroup()) {
                    voicePresets.forEach { preset ->
                        val selected = tone == preset.tone
                        Row(
                            Modifier.fillMaxWidth().selectable(selected, role = Role.RadioButton,
                                onClick = { preset.tone.save(prefs) })
                                .heightIn(min = 48.dp).padding(vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = selected, onClick = null)
                            Column {
                                Text(stringResource(preset.label), style = MaterialTheme.typography.bodyLarge)
                                Text(stringResource(preset.description), style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                HorizontalDivider(Modifier.padding(top = 8.dp))
                val expandedState = stringResource(if (fineTune) R.string.design_expanded else R.string.design_collapsed)
                Row(Modifier.fillMaxWidth().clickable(role = Role.Button) { fineTune = !fineTune }
                    .semantics { stateDescription = expandedState }.heightIn(min = 48.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.eq_fine_tune), Modifier.weight(1f),
                        style = MaterialTheme.typography.titleSmall)
                    if (custom) Text(stringResource(R.string.eq_custom), Modifier.padding(horizontal = 6.dp),
                        color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                    Icon(if (fineTune) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, null)
                }
                if (fineTune) {
                    ToneSlider(R.string.eq_bass, tone.bass) { tone.copy(bass = it).save(prefs) }
                    ToneSlider(R.string.eq_presence, tone.presence) { tone.copy(presence = it).save(prefs) }
                    ToneSlider(R.string.eq_treble, tone.treble) { tone.copy(treble = it).save(prefs) }
                }
            }
        },
    )
}

@Composable
private fun ToneSlider(label: Int, gain: Int, change: (Int) -> Unit) {
    val band = stringResource(label)
    val level = (if (gain > 0) "+" else "") + gain + " dB"
    Row(Modifier.fillMaxWidth().clearAndSetSemantics {}, verticalAlignment = Alignment.CenterVertically) {
        Text(band, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(level, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.labelLarge)
    }
    Slider(value = gain.toFloat(), onValueChange = { value ->
        val step = value.roundToInt()
        if (step != gain) change(step)
    }, valueRange = -6f..6f, steps = 11,
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = band; stateDescription = level })
}
