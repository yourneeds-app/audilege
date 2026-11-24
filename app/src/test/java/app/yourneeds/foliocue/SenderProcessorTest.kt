package app.yourneeds.foliocue

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

@androidx.annotation.OptIn(UnstableApi::class)
class SenderProcessorTest {
    private fun pcm(vararg samples: Short): ByteBuffer =
        ByteBuffer.allocateDirect(samples.size * 2).order(ByteOrder.nativeOrder()).apply {
            for (sample in samples) putShort(sample)
            flip()
        }

    @Test fun receiverReceivesUnchangedStereoAndPhoneReceivesOnlySilence() {
        val expected = shortArrayOf(21, -42, 398, -145)
        val processor = SenderProcessor(12, AirPlayClock(0, 1f, 0)) { data, rate, owner ->
            assertEquals(48_000, rate)
            assertEquals(12L, owner)
            assertArrayEquals(expected, data)
            true
        }
        processor.configure(AudioProcessor.AudioFormat(48_000, 2, C.ENCODING_PCM_16BIT))
        processor.flush()
        val input = pcm(*expected)
        processor.queueInput(input)
        assertEquals(0, input.remaining())
        val silence = processor.output
        assertEquals(8, silence.remaining())
        assertEquals(0L, silence.long)
    }

    @Test fun backpressurePreservesMonoUntilSentAndStoppingPreventsStaleWrites() {
        var calls = 0
        val processor = SenderProcessor(24, AirPlayClock(0, 1f, 0)) { data, _, _ ->
            calls++
            assertArrayEquals(shortArrayOf(1200, 1200, -2400, -2400), data)
            calls > 1
        }
        processor.configure(AudioProcessor.AudioFormat(44_100, 1, C.ENCODING_PCM_16BIT))
        processor.flush()
        val input = pcm(1200, -2400)
        processor.queueInput(input)
        assertEquals(0, input.position())
        assertEquals(0, processor.output.remaining())
        processor.queueInput(input)
        assertEquals(0, input.remaining())
        assertEquals(0, processor.output.int)
        processor.stopWrites()
        input.rewind()
        processor.queueInput(input)
        assertEquals(0, input.position())
        assertEquals(2, calls)
    }
}
