package app.yourneeds.foliocue

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.io.File
import java.nio.file.FileAlreadyExistsException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext

internal class PodcastDownloadJob : JobService() {
    private val jobs = mutableMapOf<Int, Job>()

    override fun onStartJob(params: JobParameters): Boolean {
        val network = params.network ?: return false
        val connectivity = getSystemService(ConnectivityManager::class.java)
        val display = getSharedPreferences("display", MODE_PRIVATE)
        val profileId = display.getString("profile", null) ?: return false
        fun allowed() = display.getBoolean(ENABLED, false) && display.getString("profile", null) == profileId &&
            connectivity.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        if (!allowed()) return false

        jobs[params.jobId] = CoroutineScope(Dispatchers.Main).launch {
            try {
                withContext(Dispatchers.IO) {
                    val store = Profiles(this@PodcastDownloadJob)
                    val profile = store.list().firstOrNull { it.id == profileId && it.accountLogin } ?: return@withContext
                    val catalog = OrbitCatalog(profile, store.credential(profile),
                        readSession = { store.credential(profile) }) { store.renew(profile, it) }
                    val received = getSharedPreferences("podcast-downloads", MODE_PRIVATE)
                    val reader = getSharedPreferences("reader", MODE_PRIVATE)
                    val folder = File(filesDir, "books/$profileId")
                    fun checkTransfer() {
                        ensureActive()
                        if (!allowed()) throw CancellationException("Wi-Fi or profile changed")
                    }
                    catalog.newestPodcasts(network).forEach { episode ->
                        checkTransfer()
                        val fileId = episode.editionFileId ?: return@forEach
                        val bookId = episode.url.path.substringAfterLast('/').toLong()
                        val key = "$profileId:${episode.showKey}"
                        if (received.getLong(key, -1L) == fileId) return@forEach
                        val destination = File(folder, keyOf("${episode.url}#$fileId") + ".epub")
                        if (!destination.exists()) {
                            try {
                                catalog.downloadPodcast(fileId, destination, network, ::checkTransfer)
                            } catch (_: FileAlreadyExistsException) {
                                // The episode was saved manually during the automatic download.
                            } catch (cancel: CancellationException) {
                                throw cancel
                            } catch (_: Exception) {
                                return@forEach
                            }
                            OfflineBooks.associate(reader, destination, OrbitBook(bookId, fileId, episode.author, ""))
                            OfflineBooks.rememberPodcast(reader, destination, episode)
                            display.edit().putLong(REVISION, System.currentTimeMillis()).apply()
                        }
                        received.edit().putLong(key, fileId).apply()
                    }
                }
            } catch (_: CancellationException) {
                // A stopped job is removed by onStopJob.
            } catch (_: Exception) {
                // The periodic job will retry when the active server is available.
            } finally {
                if (jobs[params.jobId] == coroutineContext.job) {
                    jobs.remove(params.jobId)
                    jobFinished(params, false)
                }
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        jobs.remove(params.jobId)?.cancel()
        return false
    }

    companion object {
        const val ENABLED = "podcastWifiAuto"
        const val REVISION = "podcastDownloadsRevision"
        private const val PERIODIC = 41301
        private const val IMMEDIATE = 41302

        fun schedule(context: Context, enabled: Boolean, checkNow: Boolean = false) {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            if (!enabled) {
                scheduler.cancel(PERIODIC)
                scheduler.cancel(IMMEDIATE)
                return
            }
            val wifi = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            val service = ComponentName(context, PodcastDownloadJob::class.java)
            if (scheduler.getPendingJob(PERIODIC) == null)
                scheduler.schedule(JobInfo.Builder(PERIODIC, service).setRequiredNetwork(wifi)
                    .setPeriodic(60 * 60_000L).setPersisted(true).build())
            if (checkNow && scheduler.getPendingJob(IMMEDIATE) == null)
                scheduler.schedule(JobInfo.Builder(IMMEDIATE, service).setRequiredNetwork(wifi).build())
        }
    }
}
