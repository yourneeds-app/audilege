package app.yourneeds.foliocue

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection

/** The Orbit resource endpoint is already resolved and authenticated. Keep every ranged GET on that endpoint. */
@androidx.annotation.OptIn(UnstableApi::class)
internal class OrbitAudioDataSource(
    private val stream: AudioStream,
    private val refresh: (() -> AudioStream)? = null,
) : BaseDataSource(true) {
    private val uri = Uri.parse(stream.url.toString())
    private var connection: HttpURLConnection? = null
    private var input: InputStream? = null
    private var remaining = C.LENGTH_UNSET.toLong()
    private var started = false

    override fun getUri(): Uri = uri

    override fun open(spec: DataSpec): Long {
        if (spec.uri != uri) throw IOException("Unexpected audio resource")
        transferInitializing(spec)
        try {
            var http = request(spec, stream)
            if (http.responseCode == 401 && refresh != null) {
                http.disconnect()
                val renewed = refresh.invoke()
                if (renewed.url != stream.url) throw IOException("Unexpected audio resource")
                http = request(spec, renewed)
            }
            val status = http.responseCode
            if (status == 416 && http.getHeaderField("Content-Range") == "bytes */${spec.position}") {
                remaining = 0L
            } else {
                if (status != 200 && status != 206) throw IOException("Audio HTTP $status")
                if (status == 206 && !validAudioRange(http.getHeaderField("Content-Range"), spec.position)) {
                    throw IOException("Incorrect audio byte range")
                }
                val body = http.inputStream
                input = body
                val skip = if (status == 200) spec.position else 0L
                var skipped = 0L
                while (skipped < skip) {
                    val count = body.skip(skip - skipped)
                    if (count > 0) skipped += count
                    else if (body.read() == -1) throw EOFException() else skipped++
                }
                val length = http.contentLengthLong
                remaining = when {
                    spec.length >= 0 -> spec.length
                    length >= 0 -> (length - skipped).coerceAtLeast(0L)
                    else -> C.LENGTH_UNSET.toLong()
                }
            }
            started = true
            transferStarted(spec)
            return remaining
        } catch (failure: Exception) {
            close()
            throw failure
        }
    }

    private fun request(spec: DataSpec, resource: AudioStream): HttpURLConnection {
        val http = resource.url.toURL().openConnection() as HttpURLConnection
        connection = http
        http.instanceFollowRedirects = false
        http.connectTimeout = 10_000
        http.readTimeout = 30_000
        resource.headers.filterKeys { !it.startsWith("android-", ignoreCase = true) }
            .forEach { (name, value) -> http.setRequestProperty(name, value) }
        http.setRequestProperty("Accept-Encoding", "identity")
        if (spec.position != 0L || spec.length != C.LENGTH_UNSET.toLong()) {
            val end = if (spec.length >= 0) (spec.position + spec.length - 1).toString() else ""
            http.setRequestProperty("Range", "bytes=${spec.position}-$end")
        }
        return http
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val size = if (remaining < 0) length else minOf(remaining, length.toLong()).toInt()
        val count = input?.read(buffer, offset, size) ?: throw IOException("Audio stream closed")
        if (count < 0 && remaining > 0) throw EOFException()
        if (count > 0) {
            if (remaining >= 0) remaining -= count
            bytesTransferred(count)
        }
        return count
    }

    override fun close() {
        try { input?.close() } finally {
            input = null
            connection?.disconnect()
            connection = null
            if (started) {
                started = false
                transferEnded()
            }
        }
    }
}

private val audioRange = Regex("""bytes (\d+)-(\d+)/(\d+|\*)""", RegexOption.IGNORE_CASE)

internal fun validAudioRange(header: String?, start: Long): Boolean {
    val match = audioRange.matchEntire(header?.trim() ?: return false) ?: return false
    val from = match.groupValues[1].toLongOrNull() ?: return false
    val to = match.groupValues[2].toLongOrNull() ?: return false
    val size = match.groupValues[3]
    return from == start && to >= from && (size == "*" || size.toLongOrNull()?.let { it > to } == true)
}
