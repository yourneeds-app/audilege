package app.yourneeds.foliocue

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.FileDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import androidx.media3.exoplayer.source.ClippingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Only this track writes to the sender. The local AudioTrack receives silence and provides decoder pacing. */
@androidx.annotation.OptIn(UnstableApi::class)
internal class AirPlayTrack(
    private val context: Context,
    private val generation: Long,
    private val uri: Uri,
    private val factory: DataSource.Factory,
    private val ownedFile: File?,
    private val onFailure: () -> Unit,
) {
    private var decoder: ExoPlayer? = null
    private var tap: SenderProcessor? = null
    private var clock: AirPlayClock? = null
    private var heldMs = 0
    private var endMs = 0
    private var tempo = 1f
    private var decoderEnded = false
    private var lastHeard = 0
    private var backlogMs = 0L
    val currentPosition: Int get() {
        if (decoder == null) return heldMs
        if (AirPlaySender.generation != generation || !AirPlaySender.connected) return lastHeard
        backlogMs = AirPlaySender.bufferedMillis()
        lastHeard = clock?.position(SystemClock.elapsedRealtime(), backlogMs) ?: heldMs
        return lastHeard
    }
    val ended: Boolean get() = decoderEnded && clock?.drained(backlogMs) == true

    fun seek(positionMs: Int, throughMs: Int, play: Boolean, speed: Float) {
        discardDecoder()
        heldMs = positionMs
        endMs = throughMs
        lastHeard = positionMs
        if (play) start(speed)
    }

    fun setPlaying(play: Boolean, speed: Float) {
        if (decoder != null && play && tempo == speed) return
        if (decoder != null) {
            heldMs = currentPosition
            discardDecoder()
        }
        if (play) start(speed)
    }

    private fun start(speed: Float) {
        if (AirPlaySender.generation != generation || !AirPlaySender.connected) return
        tempo = speed
        val timing = AirPlayClock(heldMs, speed, AirPlaySender.receiverDelayMillis())
        val processor = SenderProcessor(generation, timing, AirPlaySender::write)
        clock = timing
        tap = processor
        val renderer = object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink {
                val mix = ChannelMixingAudioProcessor().apply {
                    putChannelMixingMatrix(ChannelMixingMatrix.create(1, 1))
                    putChannelMixingMatrix(ChannelMixingMatrix.create(2, 2))
                    for (channels in 3..6) putChannelMixingMatrix(ChannelMixingMatrix.createForConstantPower(channels, 2))
                }
                val chain = object : DefaultAudioSink.DefaultAudioProcessorChain(mix) {
                    // The sender must receive PCM after Media3 has applied the selected playback speed.
                    override fun getAudioProcessors(): Array<AudioProcessor> = super.getAudioProcessors() + processor
                }
                val sink = DefaultAudioSink.Builder(context)
                    .setAudioProcessorChain(chain).setEnableFloatOutput(false).setEnableAudioTrackPlaybackParams(false).build()
                // An encoded passthrough track would bypass the PCM processor.
                return object : ForwardingAudioSink(sink) {
                    override fun supportsFormat(format: Format): Boolean =
                        format.sampleMimeType == MimeTypes.AUDIO_RAW && super.supportsFormat(format)
                    override fun getFormatSupport(format: Format): Int =
                        if (format.sampleMimeType == MimeTypes.AUDIO_RAW) super.getFormatSupport(format) else AudioSink.SINK_FORMAT_UNSUPPORTED
                }
            }
        }
        val player = ExoPlayer.Builder(context, renderer).setWakeMode(C.WAKE_MODE_NETWORK).build()
        decoder = player
        player.volume = 0f
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) { if (decoder === player) onFailure() }
            override fun onPlaybackStateChanged(state: Int) {
                if (decoder === player) decoderEnded = state == Player.STATE_ENDED
            }
        })
        val source = ProgressiveMediaSource.Factory(factory).createMediaSource(MediaItem.fromUri(uri))
        // EPUBs can reuse one audio resource for several chapters. Bound decode-ahead to this SMIL span.
        player.setMediaSource(ClippingMediaSource(source, 0L, endMs * 1000L), heldMs.toLong())
        player.setPlaybackSpeed(speed)
        AirPlaySender.setPlaying(true)
        player.prepare()
        player.play()
    }

    private fun discardDecoder() {
        val old = decoder ?: return
        decoder = null
        tap?.stopWrites()
        if (AirPlaySender.generation == generation) {
            AirPlaySender.setPlaying(false)
            AirPlaySender.flush()
        }
        old.release()
        tap = null
        clock = null
        decoderEnded = false
        backlogMs = 0
    }

    fun close() {
        discardDecoder()
        ownedFile?.delete()
    }

    companion object {
        fun online(context: Context, generation: Long, stream: AudioStream, refresh: () -> AudioStream, onFailure: () -> Unit): AirPlayTrack {
            var current = stream
            val factory = DataSource.Factory {
                OrbitAudioDataSource(current) {
                    val renewed = refresh()
                    if (renewed.url == stream.url) current = renewed
                    renewed
                }
            }
            return AirPlayTrack(context, generation, Uri.parse(stream.url.toString()), factory, null, onFailure)
        }

        fun local(context: Context, generation: Long, file: File, onFailure: () -> Unit) =
            AirPlayTrack(context, generation, Uri.fromFile(file), FileDataSource.Factory(), file, onFailure)
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
internal class SenderProcessor(
    private val generation: Long,
    private val clock: AirPlayClock,
    private val write: (ShortArray, Int, Long) -> Boolean,
) : BaseAudioProcessor() {
    private var enabled = true
    private var samples = ShortArray(0)

    @Synchronized fun stopWrites() { enabled = false }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT || inputAudioFormat.channelCount !in 1..2) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        return inputAudioFormat
    }

    @Synchronized override fun queueInput(input: ByteBuffer) {
        if (!enabled) return
        val channels = inputAudioFormat.channelCount
        val frames = minOf(1024, input.remaining() / (2 * channels))
        if (frames == 0) return
        if (samples.size != frames * 2) samples = ShortArray(frames * 2)
        val pcm = input.duplicate().order(ByteOrder.nativeOrder())
        for (frame in 0 until frames) {
            val left = pcm.short
            samples[frame * 2] = left
            samples[frame * 2 + 1] = if (channels == 1) left else pcm.short
        }
        if (!write(samples, inputAudioFormat.sampleRate, generation)) return
        clock.submitted(frames, inputAudioFormat.sampleRate)
        input.position(pcm.position())
        val silence = replaceOutputBuffer(frames * channels * 2)
        silence.limit(frames * channels * 2)
        while (silence.remaining() >= 8) silence.putLong(0L)
        while (silence.hasRemaining()) silence.put(0)
        silence.flip()
    }
}
