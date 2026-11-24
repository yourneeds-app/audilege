package app.yourneeds.foliocue

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

private fun clockLabel(ms: Int): String {
    val seconds = ms / 1000
    return if (seconds >= 3600) String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
    else String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
}

internal fun playerCover(book: Publication, online: OnlineBook?): java.io.InputStream? {
    if (book.audioStream == null) book.cover?.let { path -> book.open(path)?.let { return it } }
    return runCatching { online?.catalog?.cover(online.detail.id) }.getOrNull() ?: book.cover?.let(book::open)
}

@Composable
internal fun PlayerTheme(mode: Mode, colors: ColorScheme, book: Publication, online: OnlineBook?, file: File,
                        covers: CoverStore, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors) {
        if (mode == Mode.Listen) CoverTheme(covers, "player:${file.absolutePath}", { playerCover(book,online) },content)
        else content()
    }
}

@Composable
internal fun ListeningArtwork(
    book: Publication, online: OnlineBook?, file: File, covers: CoverStore, chapter: Int, modifier: Modifier,
) {
    BoxWithConstraints(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val width = minOf((maxHeight * .58f).coerceAtLeast(64.dp), maxWidth * .69f, 360.dp)
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            BookCover(book.title, covers, "player:${file.absolutePath}", { playerCover(book,online) },
                Modifier.width(width).aspectRatio(.82f))
            Spacer(Modifier.height(3.dp))
            Text(stringResource(R.string.audio_chapter,chapter + 1), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            val label = book.contents.firstOrNull { it.path == book.spine[chapter].path }?.label.orEmpty()
            Text(label.ifBlank { book.title }, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold,
                maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            ListenOutputStatus()
        }
    }
}

@Composable
private fun PlayControl(playing: Boolean, loading: Boolean, enabled: Boolean, large: Boolean, onClick: () -> Unit) {
    val label = stringResource(if (playing) R.string.pause else R.string.play)
    FilledIconButton(onClick = onClick, enabled = enabled, shape = CircleShape,
        modifier = Modifier.size(if (large) 64.dp else 48.dp).semantics { contentDescription = label }) {
        val ink = LocalContentColor.current
        if (loading) CircularProgressIndicator(Modifier.size(22.dp),color = ink,strokeWidth = 2.dp)
        else if (playing) Canvas(Modifier.size(25.dp)) {
            drawLine(ink, Offset(size.width * .3f,size.height * .13f), Offset(size.width * .3f,size.height * .87f),size.width * .2f)
            drawLine(ink, Offset(size.width * .7f,size.height * .13f), Offset(size.width * .7f,size.height * .87f),size.width * .2f)
        } else Icon(Icons.Filled.PlayArrow,contentDescription = null,modifier = Modifier.size(if (large) 36.dp else 28.dp))
    }
}

@Composable
private fun ChapterButton(previous: Boolean, enabled: Boolean, click: () -> Unit) {
    val label = stringResource(if (previous) R.string.chapter_previous else R.string.chapter_next)
    IconButton(onClick = click, enabled = enabled, modifier = Modifier.semantics { contentDescription = label }) {
        val ink = LocalContentColor.current
        Canvas(Modifier.size(21.dp)) {
            fun x(frac: Float) = size.width * (if (previous) 1f-frac else frac)
            val play = Path().apply {
                moveTo(x(.1f),size.height*.10f); lineTo(x(.75f),size.height*.50f)
                lineTo(x(.1f),size.height*.90f); close()
            }
            drawPath(play, ink)
            drawLine(ink, Offset(x(.86f),size.height*.1f), Offset(x(.86f),size.height*.9f),size.width*.14f)
        }
    }
}

@Composable
private fun SkipButton(back: Boolean, enabled: Boolean) {
    val player = AudilegePlayback
    val label = stringResource(if (back) R.string.audio_skip_back else R.string.audio_skip_forward,player.skipSeconds)
    IconButton(onClick = { player.skip(if (back) -1 else 1) }, enabled = enabled, modifier = Modifier.semantics { contentDescription = label }) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.clearAndSetSemantics { }) {
            Text(if (back) "↶" else "↷", Modifier.align(Alignment.TopCenter).offset(y = (-7).dp), style = MaterialTheme.typography.titleLarge)
            Text(player.skipSeconds.toString(), Modifier.offset(y = 6.dp), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
internal fun ReaderDock(
    mode: Mode, chapter: Int, chapterCount: Int, progress: TrackProgress?, speed: Float,
    playing: Boolean, loading: Boolean, audioAvailable: Boolean, onPrevious: () -> Unit,
    onNext: () -> Unit, onContents: () -> Unit, onSpeed: () -> Unit, onPlay: () -> Unit,
    onRepeat: (() -> Unit)? = null,
) {
    Surface(color = if (mode == Mode.Listen) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Column(Modifier.widthIn(max = 600.dp).fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                if (mode == Mode.Listen) {
                    var drag by remember(chapter) { mutableStateOf<Float?>(null) }
                    val duration = progress?.durationMs ?: 0
                    val position = drag?.roundToInt() ?: progress?.elapsedMs ?: 0
                    val label = stringResource(R.string.track_progress)
                    Slider(value = position.toFloat().coerceIn(0f,duration.toFloat()), onValueChange = { drag = it },
                        onValueChangeFinished = { drag?.let { AudilegePlayback.seekTo(it.roundToInt()) }; drag = null },
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = label },
                        valueRange = 0f..duration.coerceAtLeast(1).toFloat(),enabled = duration > 0 && !loading)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(clockLabel(position),style = MaterialTheme.typography.labelMedium)
                        Text(clockLabel(duration),style = MaterialTheme.typography.labelMedium)
                    }
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically) {
                        SkipButton(true, progress != null && !loading)
                        ChapterButton(true,chapter > 0,onPrevious)
                        PlayControl(playing,loading,audioAvailable,true,onPlay)
                        ChapterButton(false,chapter < chapterCount-1,onNext)
                        SkipButton(false, progress != null && !loading)
                    }
                    Row(Modifier.fillMaxWidth(),horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        val tempo = java.text.NumberFormat.getNumberInstance().apply {
                            minimumFractionDigits = 1
                            maximumFractionDigits = 2
                        }.format(speed) + "×"
                        PlaybackSetting(stringResource(R.string.speed), tempo, Modifier.weight(1f), onSpeed)
                        SleepTimer(Modifier.weight(1f))
                    }
                } else {
                    Row(Modifier.fillMaxWidth(),horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        ChapterButton(true,chapter > 0,onPrevious)
                        TextButton(onClick = onContents,modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.audio_chapter,chapter + 1), textAlign = TextAlign.Center)
                        }
                        if (mode == Mode.Follow) {
                            if (onRepeat != null) {
                                val repeatLabel = stringResource(R.string.audio_repeat)
                                IconButton(onClick = onRepeat, enabled = audioAvailable, modifier = Modifier.semantics { contentDescription = repeatLabel }) { Text("↶") }
                            }
                            PlayControl(playing,loading,audioAvailable,false,onPlay)
                        }
                        ChapterButton(false,chapter < chapterCount-1,onNext)
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaybackSetting(title: String, value: String, modifier: Modifier, click: () -> Unit) {
    Surface(onClick = click, modifier = modifier, shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun SleepTimer(modifier: Modifier) {
    val player = AudilegePlayback
    var menu by remember { mutableStateOf(false) }
    val deadline = player.sleepUntil
    val mins by produceState(0,deadline) {
        while (deadline > 0L) {
            value = ((deadline-SystemClock.elapsedRealtime()+59_999L)/60_000L).coerceAtLeast(0).toInt()
            delay(1000)
        }
    }
    val status = when {
        player.sleepEndChapter -> stringResource(R.string.audio_chapter_end)
        deadline > 0 -> stringResource(R.string.audio_minutes,mins)
        else -> stringResource(R.string.audio_off)
    }
    Box(modifier) {
        PlaybackSetting(stringResource(R.string.audio_timer),status,Modifier.fillMaxWidth()) { menu = true }
        DropdownMenu(expanded = menu,onDismissRequest = { menu = false }) {
            listOf(0,5,15,30,45,60,-1).forEach { choice ->
                DropdownMenuItem(text = { Text(when (choice) {
                    0 -> stringResource(R.string.audio_off)
                    -1 -> stringResource(R.string.audio_chapter_end)
                    else -> stringResource(R.string.audio_minutes,choice)
                }) },onClick = { player.sleep(choice); menu = false })
            }
        }
    }
}

@Composable
internal fun MiniPlayer(onOpen: (OpenBook) -> Unit) {
    val player = AudilegePlayback
    val book = player.activeBook ?: return
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            val openLabel = stringResource(R.string.audio_open)
            Column(Modifier.weight(1f).clickable(onClickLabel = openLabel) { onOpen(book) }
                .heightIn(min = 48.dp).padding(start = 8.dp),verticalArrangement = Arrangement.Center) {
                Text(book.publication.title, style = MaterialTheme.typography.titleSmall, maxLines = 1,overflow = TextOverflow.Ellipsis)
                Text(stringResource(R.string.audio_chapter,player.chapter + 1),style = MaterialTheme.typography.bodySmall)
            }
            PlayControl(player.playing,player.loading,true,false,player::toggle)
            TextButton(onClick = player::stop) { Text(stringResource(R.string.close)) }
        }
    }
}
