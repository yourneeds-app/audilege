package app.yourneeds.foliocue

import android.media.MediaRoute2Info
import android.media.MediaRoute2ProviderService
import android.media.RouteDiscoveryPreference
import android.media.RoutingSessionInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi
import java.util.UUID

@RequiresApi(30)
class AudilegeRouteProviderService : MediaRoute2ProviderService() {
    private data class Request(val id: Long, val route: String, val name: String, val session: String?)
    private var pending: Request? = null
    internal val transferring get() = pending != null
    private var discoveryRequested = false
    private val discovery by lazy { AirPlayDiscovery.shared(this) }
    private val handler = Handler(Looper.getMainLooper())
    private val timeout = Runnable {
        val request = pending
        if (request != null && AudilegePlayback.outputId != request.route) {
            fail(REASON_NETWORK_ERROR)
            if (AudilegePlayback.connectingOutput == request.name) AudilegePlayback.localMode()
        }
    }
    private var published: List<MediaRoute2Info> = emptyList()

    override fun onCreate() {
        super.onCreate()
        AudilegeSystemRoutes.provider = this
        refresh()
    }

    override fun onDiscoveryPreferenceChanged(preference: RouteDiscoveryPreference) {
        discoveryRequested = AudilegeSystemRoutes.FEATURE in preference.preferredFeatures ||
            (preference.shouldPerformActiveScan() && MediaRoute2Info.FEATURE_REMOTE_AUDIO_PLAYBACK in preference.preferredFeatures)
        refresh()
    }

    internal fun refresh() {
        val state = AudilegeSystemRoutes.state
        if (state.eligible && (discoveryRequested || state.selected != null || transferring)) discovery.acquire(this)
        else discovery.release(this)

        val devices = linkedMapOf<String, String>()
        if (state.eligible) {
            state.speakers.forEach { devices[it.id] = it.name }
            // A negotiated connection remains usable even if its mDNS announcement expires.
            if (state.selected != null && state.selectedName != null) devices[state.selected] = state.selectedName
        }
        val routes = devices.map { (id, name) ->
            val active = id == state.selected
            MediaRoute2Info.Builder(id, name)
                .addFeature(AudilegeSystemRoutes.FEATURE)
                .addFeature(MediaRoute2Info.FEATURE_REMOTE_AUDIO_PLAYBACK)
                .addFeature(MediaRoute2Info.FEATURE_REMOTE_PLAYBACK)
                .setExtras(Bundle().apply { putString(AudilegeSystemRoutes.SPEAKER_ID, id) })
                .setConnectionState(if (active) MediaRoute2Info.CONNECTION_STATE_CONNECTED else MediaRoute2Info.CONNECTION_STATE_DISCONNECTED)
                .setVolumeHandling(MediaRoute2Info.PLAYBACK_VOLUME_VARIABLE)
                .setVolumeMax(100).setVolume(state.volume.coerceIn(0, 100))
                .apply {
                    if (Build.VERSION.SDK_INT >= 34) {
                        setType(MediaRoute2Info.TYPE_REMOTE_SPEAKER)
                        setVisibilityRestricted(setOf(packageName))
                    }
                }.build()
        }
        if (routes != published) {
            published = routes
            notifyRoutes(routes)
        }
        val request = pending
        val selected = routes.firstOrNull { it.id == state.selected }
        if (request != null) {
            when {
                !state.eligible -> fail(REASON_REJECTED)
                state.selected != AudilegePlayback.outputId || state.connecting != AudilegePlayback.connectingOutput -> return
                state.connecting != null && state.connecting != request.name -> fail(REASON_REJECTED)
                state.connecting != null -> return
                selected?.id == request.route -> {
                    clearPending()
                    if (request.session == null) {
                        notifySessionCreated(request.id, describeSession(UUID.randomUUID().toString(), selected))
                    } else if (getSessionInfo(request.session) != null) {
                        notifySessionUpdated(describeSession(request.session, selected))
                    } else notifyRequestFailed(request.id, REASON_INVALID_COMMAND)
                }
                else -> fail(REASON_ROUTE_NOT_AVAILABLE)
            }
        }
        // Preserve the previous controller while a replacement endpoint is negotiating.
        if (state.eligible && state.connecting != null) return
        allSessionInfo.forEach { session ->
            if (selected == null) notifySessionReleased(session.id)
            else {
                val next = describeSession(session.id, selected)
                if (next != session) notifySessionUpdated(next)
            }
        }
    }

    private fun describeSession(id: String, selected: MediaRoute2Info): RoutingSessionInfo {
        return RoutingSessionInfo.Builder(id, packageName)
            .setName(selected.name).addSelectedRoute(selected.id)
            .setVolumeHandling(MediaRoute2Info.PLAYBACK_VOLUME_VARIABLE)
            .setVolumeMax(100).setVolume(selected.volume)
            .apply { published.filter { it.id != selected.id }.forEach { addTransferableRoute(it.id) } }
            .build()
    }

    override fun onCreateSession(requestId: Long, packageName: String, routeId: String, hints: Bundle?) {
        if (packageName != this.packageName) notifyRequestFailed(requestId, REASON_REJECTED)
        else request(requestId, routeId, null)
    }

    override fun onTransferToRoute(requestId: Long, sessionId: String, routeId: String) {
        if (getSessionInfo(sessionId) == null) notifyRequestFailed(requestId, REASON_INVALID_COMMAND)
        else request(requestId, routeId, sessionId)
    }

    override fun onSelectRoute(requestId: Long, sessionId: String, routeId: String) =
        onTransferToRoute(requestId, sessionId, routeId)

    private fun request(requestId: Long, id: String, session: String?) {
        val player = AudilegePlayback
        val state = AudilegeSystemRoutes.state
        if (!state.eligible || !player.listenEligible || player.activeBook == null || transferring || player.connectingOutput != null) {
            notifyRequestFailed(requestId, REASON_REJECTED)
            return
        }
        val speaker = state.speakers.firstOrNull { it.id == id }
        if (published.none { it.id == id } || (speaker == null && player.outputId != id)) {
            notifyRequestFailed(requestId, REASON_ROUTE_NOT_AVAILABLE)
            return
        }
        pending = Request(requestId, id, speaker?.name ?: id, session)
        if (player.outputId == id) {
            refresh()
        } else {
            // Native attachment is asynchronous. The observation confirms the session after success.
            handler.postDelayed(timeout, 20_000)
            player.selectOutput(speaker)
        }
    }

    override fun onReleaseSession(requestId: Long, sessionId: String) {
        val session = getSessionInfo(sessionId)
        if (session == null) {
            notifyRequestFailed(requestId, REASON_INVALID_COMMAND)
            return
        }
        val cancelling = pending?.session == sessionId
        if (cancelling) fail(REASON_REJECTED)
        notifySessionReleased(sessionId)
        val current = AudilegePlayback.outputId
        if (cancelling || (current != null && current in session.selectedRoutes && allSessionInfo.none { current in it.selectedRoutes })) {
            AudilegePlayback.selectOutput(null)
        }
    }

    override fun onDeselectRoute(requestId: Long, sessionId: String, routeId: String) {
        // One receiver is selected per session. Switching to the phone releases the session.
        notifyRequestFailed(requestId, REASON_INVALID_COMMAND)
    }

    override fun onSetRouteVolume(requestId: Long, routeId: String, volume: Int) {
        if (AudilegePlayback.listenEligible && routeId == AudilegePlayback.outputId) {
            AudilegePlayback.changeOutputVolume(volume.coerceIn(0, 100))
        } else notifyRequestFailed(requestId, REASON_ROUTE_NOT_AVAILABLE)
    }

    override fun onSetSessionVolume(requestId: Long, sessionId: String, volume: Int) {
        val selected = getSessionInfo(sessionId)?.selectedRoutes?.singleOrNull()
        if (selected == null) notifyRequestFailed(requestId, REASON_INVALID_COMMAND)
        else onSetRouteVolume(requestId, selected, volume)
    }

    private fun clearPending() {
        pending = null
        handler.removeCallbacks(timeout)
    }

    private fun fail(reason: Int) {
        pending?.let { notifyRequestFailed(it.id, reason) }
        clearPending()
    }

    override fun onDestroy() {
        val request = pending
        if (request != null) {
            fail(REASON_REJECTED)
            if (AudilegePlayback.connectingOutput == request.name) AudilegePlayback.localMode()
        }
        if (AudilegeSystemRoutes.provider === this) AudilegeSystemRoutes.provider = null
        discovery.release(this)
        super.onDestroy()
    }
}
