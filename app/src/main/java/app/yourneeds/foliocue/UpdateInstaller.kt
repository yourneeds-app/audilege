package app.yourneeds.foliocue

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class UpdateFileProvider : FileProvider()

internal object UpdateInstaller {
    private const val APK_TYPE = "application/vnd.android.package-archive"
    private const val MAX_ASSET_BYTES = 2L * 1024 * 1024 * 1024
    private val versionFormat = Regex("""\d+\.\d+\.\d+""")
    private val cacheName = Regex("""audilege-\d+\.\d+\.\d+\.apk""")
    private val digestFormat = Regex("[a-fA-F0-9]{64}")
    private val redirectHosts = setOf("release-assets.githubusercontent.com", "objects.githubusercontent.com",
        "github-releases.githubusercontent.com")
    private val downloadLock = Mutex()

    suspend fun download(context: Context, release: Release, report: (Int?) -> Unit): File =
        withContext(Dispatchers.IO) {
            downloadLock.withLock {
                val origin = releaseUri(release)
                val expectedSize = release.size.takeIf { it > 0 }
                require(expectedSize == null || expectedSize < MAX_ASSET_BYTES)
                val checksum = release.sha256
                require(checksum == null || digestFormat.matches(checksum))
                val directory = updateDirectory(context)
                if (!directory.mkdirs() && !directory.isDirectory) throw IOException("Update cache unavailable")
                directory.listFiles { file -> file.name.startsWith("audilege-") && file.name.endsWith(".partial.apk") }
                    ?.forEach { it.delete() }
                val apk = File(directory, "audilege-${release.version}.apk")
                if (apk.isFile && (expectedSize == null || apk.length() == expectedSize) &&
                    matchesDigest(apk, checksum) && matchesInstalledApp(context, apk, release.version)) {
                    withContext(Dispatchers.Main.immediate) { report(100) }
                    return@withLock apk
                }
                val partial = File.createTempFile("audilege-", ".partial.apk", directory)
                try {
                    val request = connect(origin)
                    val digest = checksum?.let { MessageDigest.getInstance("SHA-256") }
                    try {
                        val contentLength = request.contentLengthLong.takeIf { it >= 0 }
                        if (contentLength != null && expectedSize != null && contentLength != expectedSize)
                            throw IOException("Unexpected update size")
                        val total = expectedSize ?: contentLength
                        if (total != null && total !in 1L until MAX_ASSET_BYTES)
                            throw IOException("Invalid update size")
                        var lastPercent: Int? = if (total == null) null else 0
                        withContext(Dispatchers.Main.immediate) { report(lastPercent) }
                        var received = 0L
                        val buffer = ByteArray(64 * 1024)
                        request.inputStream.use { input ->
                            partial.outputStream().buffered().use { output ->
                                while (true) {
                                    coroutineContext.ensureActive()
                                    val size = input.read(buffer)
                                    if (size < 0) break
                                    received += size
                                    if (received >= MAX_ASSET_BYTES || (total != null && received > total))
                                        throw IOException("Update exceeds expected size")
                                    output.write(buffer, 0, size)
                                    digest?.update(buffer, 0, size)
                                    val percent = total?.let { (received * 100 / it).toInt().coerceAtMost(99) }
                                    if (percent != lastPercent) {
                                        lastPercent = percent
                                        withContext(Dispatchers.Main.immediate) { report(percent) }
                                    }
                                }
                            }
                        }
                        if (received == 0L || (total != null && received != total))
                            throw IOException("Incomplete update")
                    } finally {
                        request.disconnect()
                    }
                    val downloadedDigest = digest?.digest()?.hex()
                    if (checksum != null && !checksum.equals(downloadedDigest, ignoreCase = true))
                        throw IOException("Update checksum mismatch")
                    if (!matchesInstalledApp(context, partial, release.version))
                        throw IOException("APK does not match the installed app")
                    coroutineContext.ensureActive()
                    Files.move(partial.toPath(), apk.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                    withContext(Dispatchers.Main.immediate) { report(100) }
                    apk
                } finally {
                    partial.delete()
                }
            }
        }

    private fun releaseUri(release: Release): URI {
        require(versionFormat.matches(release.version))
        val uri = URI(release.link)
        val version = release.version
        val validPath = Regex("""/yourneeds-app/audilege/releases/download/v?${Regex.escape(version)}/Audilege-${Regex.escape(version)}(?:-release|-debug)?\.apk""")
        require(validEndpoint(uri, setOf("github.com")) && validPath.matches(uri.rawPath) && uri.rawQuery == null)
        return uri
    }

    private fun validEndpoint(uri: URI, hosts: Set<String>): Boolean =
        uri.scheme == "https" && uri.userInfo == null && uri.host in hosts &&
            uri.port in setOf(-1, 443) && uri.fragment == null

    private fun connect(origin: URI): HttpURLConnection {
        var target = origin
        repeat(6) {
            val request = target.toURL().openConnection() as HttpURLConnection
            request.instanceFollowRedirects = false
            request.connectTimeout = 10_000
            request.readTimeout = 20_000
            request.setRequestProperty("Accept", "application/octet-stream")
            request.setRequestProperty("Accept-Encoding", "identity")
            request.setRequestProperty("User-Agent", "Audilege-Android")
            try {
                val status = request.responseCode
                if (status == HttpURLConnection.HTTP_OK) return request
                if (status !in setOf(301, 302, 303, 307, 308)) throw IOException("Update download HTTP $status")
                val location = request.getHeaderField("Location") ?: throw IOException("Missing download redirect")
                target = target.resolve(location)
                if (!validEndpoint(target, redirectHosts)) throw IOException("Unexpected update host")
            } catch (failure: Exception) {
                request.disconnect()
                throw failure
            }
            request.disconnect()
        }
        throw IOException("Too many update download redirects")
    }

    private suspend fun matchesDigest(file: File, expected: String?): Boolean {
        if (expected == null) return true
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        file.inputStream().use { input ->
            while (true) {
                coroutineContext.ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().hex().equals(expected, ignoreCase = true)
    }

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

    @Suppress("DEPRECATION")
    private fun matchesInstalledApp(context: Context, apk: File, version: String): Boolean {
        val manager = context.packageManager
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val candidate = manager.getPackageArchiveInfo(apk.absolutePath, flags) ?: return false
        val installed = manager.getPackageInfo(context.packageName, flags)
        val signers = installed.signingInfo?.apkContentsSigners?.toSet().orEmpty()
        return candidate.packageName == context.packageName && candidate.versionName == version &&
            candidate.longVersionCode > installed.longVersionCode && signers.isNotEmpty() &&
            candidate.signingInfo?.apkContentsSigners?.toSet() == signers
    }

    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    fun requestPermission(context: Context) {
        open(context, Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.fromParts("package", context.packageName, null)))
    }

    fun install(context: Context, apk: File) {
        require(apk.isFile && cacheName.matches(apk.name) && apk.canonicalFile.parentFile == updateDirectory(context).canonicalFile)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.update-files", apk)
        open(context, Intent(Intent.ACTION_VIEW).setDataAndType(uri, APK_TYPE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }

    private fun open(context: Context, intent: Intent) {
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    private fun updateDirectory(context: Context) = File(context.cacheDir, "app-updates")
}
