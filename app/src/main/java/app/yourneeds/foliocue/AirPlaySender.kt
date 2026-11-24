package app.yourneeds.foliocue

import android.content.Context
import android.os.Build
import android.os.Process
import androidx.annotation.Keep
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

@Keep
private object AirPlayNative {
    external fun nativeConnect(host: String, port: Int, name: String, identity: String)
    external fun nativeDisconnect()
    external fun nativeConnected(): Boolean
    external fun nativeFailed(): Boolean
    external fun nativeWrite(stereo: ShortArray, sampleRate: Int): Boolean
    external fun nativePlaying(playing: Boolean)
    external fun nativeFlush()
    external fun nativeVolume(percent: Int)
    external fun nativeMetadata(title: String, artist: String)
    external fun nativeBufferedMillis(): Long
    external fun nativeReceiverDelayMillis(): Long
}

/** One user-selected receiver. Decoding and the network pump run on separate threads. */
internal object AirPlaySender {
    val supported: Boolean
        get() = if (Process.is64Bit()) {
            Build.SUPPORTED_64_BIT_ABIS.any { it == "arm64-v8a" || it == "x86_64" }
        } else {
            Build.SUPPORTED_32_BIT_ABIS.contains("armeabi-v7a")
        }

    private val lifecycle = Mutex()
    private val writer = Any()
    private val libraryLoaded = lazy {
        try {
            System.loadLibrary("audilege_airplay")
            true
        } catch (_: UnsatisfiedLinkError) {
            false
        }
    }

    @Volatile var generation = 0L
        private set
    @Volatile private var ready = false
    @Volatile private var receiverVolume = 25
    val volume: Int get() = receiverVolume
    val connected: Boolean get() = ready && AirPlayNative.nativeConnected()

    private fun nextSession(): Long = synchronized(writer) {
        ready = false
        ++generation
    }

    suspend fun connect(context: Context, speaker: AirPlaySpeaker): Boolean {
        val session = nextSession()
        var attached = false
        try {
            attached = withContext(Dispatchers.IO) {
                if (!supported || !libraryLoaded.value) return@withContext false
                lifecycle.withLock {
                    if (session != generation) return@withContext false
                    val prefs = context.applicationContext.getSharedPreferences("airplay_sender", Context.MODE_PRIVATE)
                    val identity = prefs.getString("identity", null) ?: UUID.randomUUID().toString()
                        .replace("-", "").take(12).chunked(2).joinToString(":").also {
                            prefs.edit().putString("identity", it).apply()
                        }
                    receiverVolume = 25
                    AirPlayNative.nativeConnect(speaker.address, speaker.port, speaker.name, identity)
                }
                withTimeoutOrNull(15_000L) {
                    while (session == generation && !AirPlayNative.nativeFailed()) {
                        if (AirPlayNative.nativeConnected()) {
                            return@withTimeoutOrNull synchronized(writer) {
                                (session == generation).also { if (it) ready = true }
                            }
                        }
                        delay(75L)
                    }
                    false
                } ?: false
            }
            return attached
        } finally {
            if (!attached) stopIfOwner(session)
        }
    }

    suspend fun disconnect() = stopIfOwner(nextSession())

    private suspend fun stopIfOwner(session: Long) = withContext(NonCancellable + Dispatchers.IO) {
        lifecycle.withLock {
            if (generation == session && libraryLoaded.isInitialized() && libraryLoaded.value) {
                synchronized(writer) { if (generation == session) ready = false }
                AirPlayNative.nativeDisconnect()
            }
        }
    }

    fun write(stereo: ShortArray, sampleRate: Int, expectedGeneration: Long): Boolean = synchronized(writer) {
        ready && generation == expectedGeneration && AirPlayNative.nativeWrite(stereo, sampleRate)
    }

    fun flush() = synchronized(writer) { if (ready) AirPlayNative.nativeFlush() }
    fun setPlaying(playing: Boolean) = synchronized(writer) { if (ready) AirPlayNative.nativePlaying(playing) }
    fun setVolume(percent: Int) = synchronized(writer) {
        if (ready) {
            receiverVolume = percent.coerceIn(0, 100)
            AirPlayNative.nativeVolume(receiverVolume)
        }
    }

    fun metadata(title: String, artist: String) = synchronized(writer) {
        if (ready) AirPlayNative.nativeMetadata(title, artist)
    }

    fun bufferedMillis(): Long = if (connected) AirPlayNative.nativeBufferedMillis() else 0L

    /** The negotiated protocol clock offset. Receiver hardware can add latency beyond this value. */
    fun receiverDelayMillis(): Long = if (connected) AirPlayNative.nativeReceiverDelayMillis() else 0L
}
