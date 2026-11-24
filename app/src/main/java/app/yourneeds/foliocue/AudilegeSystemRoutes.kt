package app.yourneeds.foliocue

import android.content.Context
import android.media.MediaRoute2Info
import android.media.MediaRouter2
import android.media.RouteDiscoveryPreference
import androidx.annotation.RequiresApi
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class ListenRoutes(
    val eligible: Boolean = false,
    val speakers: List<AirPlaySpeaker> = emptyList(),
    val selected: String? = null,
    val selectedName: String? = null,
    val connecting: String? = null,
    val volume: Int = 0,
    val error: Int? = null
)

/** The Android media tile and the in-app chooser share one transport and one DNS-SD search. */
@RequiresApi(30)
internal object AudilegeSystemRoutes {
    const val FEATURE = "app.yourneeds.foliocue.AIRPLAY_AUDIO"
    const val SPEAKER_ID = "audilege.speakerId"
    private var router: MediaRouter2? = null
    private var registered = false
    private var observation: Job? = null
    private var onControlId: ((String?) -> Unit)? = null
    private var controlId: String? = null
    private var mirrorRequested: String? = null
    var provider: AudilegeRouteProviderService? = null
    var state = ListenRoutes()
        private set

    private val routeCallback = object : MediaRouter2.RouteCallback() {
        override fun onRoutesAdded(routes: List<MediaRoute2Info>) = mirrorSelection()
        override fun onRoutesChanged(routes: List<MediaRoute2Info>) = mirrorSelection()
    }

    private val transferCallback = object : MediaRouter2.TransferCallback() {
        override fun onTransfer(old: MediaRouter2.RoutingController, next: MediaRouter2.RoutingController) {
            val id = next.selectedRoutes.firstNotNullOfOrNull { it.extras?.getString(SPEAKER_ID) }
            if (id == null && old.id == controlId) chooseLocalIfCurrent(old)
            setControlId(if (id != null) next.id else null)
        }

        override fun onStop(controller: MediaRouter2.RoutingController) {
            if (controller.id == controlId) setControlId(null)
        }

        override fun onTransferFailure(route: MediaRoute2Info) {
            if (mirrorRequested == route.extras?.getString(SPEAKER_ID)) mirrorRequested = null
        }
    }

    private fun chooseLocalIfCurrent(controller: MediaRouter2.RoutingController) {
        val id = AudilegePlayback.outputId ?: return
        if (controller.selectedRoutes.any { it.extras?.getString(SPEAKER_ID) == id }) {
            AudilegePlayback.selectOutput(null)
        }
    }

    private fun setControlId(id: String?) {
        if (controlId == id) return
        controlId = id
        onControlId?.invoke(id)
    }

    fun attach(context: Context, onControlId: (String?) -> Unit) {
        detach()
        val app = context.applicationContext
        val discovery = AirPlayDiscovery.shared(app)
        // Some Android configurations expose no system audio routes. Playback still
        // uses its media session and the in-app output chooser in that case.
        val mediaRouter = try { MediaRouter2.getInstance(app) } catch (_: RuntimeException) { return }
        router = mediaRouter
        this.onControlId = onControlId
        observation = CoroutineScope(Dispatchers.Main.immediate).launch {
            if (!withContext(Dispatchers.IO) { AirPlaySender.supported }) return@launch
            snapshotFlow {
                val p = AudilegePlayback
                ListenRoutes(p.listenEligible && p.activeBook != null, discovery.speakers,
                    p.outputId, p.outputName, p.connectingOutput, p.outputVolume, p.outputError)
            }.collect { next ->
                if (state.selected != next.selected) mirrorRequested = null
                state = next
                if (next.eligible && !registered) {
                    registered = true
                    mediaRouter.registerTransferCallback(app.mainExecutor, transferCallback)
                    mediaRouter.registerRouteCallback(app.mainExecutor, routeCallback,
                        RouteDiscoveryPreference.Builder(listOf(FEATURE), false).build())
                }
                provider?.refresh()
                if (next.eligible) mirrorSelection() else unregister()
            }
        }
    }

    /** Mirror only a speaker the listener already selected and the sender actually connected to. */
    fun mirrorSelection() {
        if (!state.eligible || state.connecting != null || provider?.transferring == true) return
        val id = state.selected ?: return
        val mediaRouter = router ?: return
        val controller = mediaRouter.controllers.firstOrNull { c ->
            !c.isReleased && c.selectedRoutes.any { it.extras?.getString(SPEAKER_ID) == id }
        }
        if (controller != null) {
            setControlId(controller.id)
            return
        }
        if (provider?.allSessionInfo?.any { id in it.selectedRoutes } == true) return
        if (mirrorRequested == id) return
        val route = mediaRouter.routes.firstOrNull { it.extras?.getString(SPEAKER_ID) == id } ?: return
        mirrorRequested = id
        mediaRouter.transferTo(route)
    }

    private fun unregister() {
        if (registered) {
            registered = false
            router?.unregisterRouteCallback(routeCallback)
            router?.unregisterTransferCallback(transferCallback)
        }
        mirrorRequested = null
        setControlId(null)
    }

    fun detach() {
        observation?.cancel()
        observation = null
        state = ListenRoutes()
        provider?.refresh()
        unregister()
        router = null
        onControlId = null
    }
}
