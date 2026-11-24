package app.yourneeds.foliocue

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.net.Inet4Address
import java.net.InetAddress
import java.util.Locale

internal data class AirPlaySpeaker(val id: String, val name: String, val address: String, val port: Int)

private fun airPlayAdvertisesAudio(features: String?): Boolean {
    // Older receivers may omit this optional TXT record. Known video-only receivers cannot play a book.
    val lowWord = features?.substringBefore(',')?.trim()?.removePrefix("0x")?.removePrefix("0X")
    val mask = lowWord?.toULongOrNull(16) ?: return true
    return mask and 0x200uL != 0uL
}

internal fun discoveredAirPlaySpeaker(name: String, port: Int, addresses: List<InetAddress>,
    attributes: Map<String, ByteArray?>): AirPlaySpeaker? {
    fun txt(key: String) = attributes.entries.firstOrNull { it.key.equals(key, ignoreCase = true) }
        ?.value?.toString(Charsets.UTF_8)?.trim()
    if (port !in 1..65535 || !airPlayAdvertisesAudio(txt("features"))) return null
    val address = addresses.filterIsInstance<Inet4Address>().firstOrNull {
        !it.isAnyLocalAddress && !it.isLoopbackAddress && !it.isMulticastAddress
    }?.hostAddress ?: return null
    val id = txt("deviceid")?.takeIf { it.isNotEmpty() }?.lowercase(Locale.ROOT) ?: name
    return AirPlaySpeaker(id, name, address, port)
}

/** The app chooser and Android's output switcher share one scan while either is looking for routes. */
internal class AirPlayDiscovery private constructor(context: Context) {
    companion object {
        private var instance: AirPlayDiscovery? = null
        @Synchronized fun shared(context: Context): AirPlayDiscovery =
            instance ?: AirPlayDiscovery(context.applicationContext).also { instance = it }
    }
    private val app = context.applicationContext
    private val manager = app.getSystemService(NsdManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var discovery: NsdManager.DiscoveryListener? = null
    private val found = linkedMapOf<String, NsdServiceInfo>()
    private val observed = mutableMapOf<String, () -> Unit>()
    private val resolved = mutableMapOf<String, AirPlaySpeaker>()
    private val queue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private val owners = mutableSetOf<Any>()

    var speakers by mutableStateOf(emptyList<AirPlaySpeaker>())
        private set
    var failed by mutableStateOf(false)
        private set

    fun acquire(owner: Any) { if (owners.add(owner) && discovery == null) start() }
    fun release(owner: Any) { if (owners.remove(owner) && owners.isEmpty()) stop() }
    fun retry() {
        if (owners.isNotEmpty()) {
            stop()
            start()
        }
    }

    private fun start() {
        if (discovery != null) return
        failed = false
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) = Unit
            override fun onServiceFound(service: NsdServiceInfo) {
                handler.post { if (discovery === this && found.putIfAbsent(service.serviceName, service) == null) {
                    if (Build.VERSION.SDK_INT >= 34) observe(service)
                    else { queue.addLast(service); resolveNext() }
                } }
            }
            override fun onServiceLost(service: NsdServiceInfo) {
                handler.post {
                    if (discovery === this) {
                        found.remove(service.serviceName)
                        observed.remove(service.serviceName)?.invoke()
                        queue.removeAll { it.serviceName == service.serviceName }
                        remove(service.serviceName)
                    }
                }
            }
            override fun onDiscoveryStopped(type: String) { handler.post { if (discovery === this) stop() } }
            override fun onStartDiscoveryFailed(type: String, code: Int) {
                handler.post { if (discovery === this) { stop(); failed = true } }
            }
            override fun onStopDiscoveryFailed(type: String, code: Int) {
                handler.post { if (discovery === this) { stop(); failed = true } }
            }
        }
        discovery = listener
        try {
            manager.discoverServices("_airplay._tcp.", NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (_: RuntimeException) {
            discovery = null
            failed = true
        }
    }

    @RequiresApi(34)
    private fun observe(service: NsdServiceInfo) {
        val name = service.serviceName
        val callback = object : NsdManager.ServiceInfoCallback {
            override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                if (found[name] === service) {
                    observed.remove(name)
                    queue.addLast(service)
                    resolveNext()
                }
            }
            override fun onServiceUpdated(info: NsdServiceInfo) {
                if (found[name] === service) update(name, info)
            }
            override fun onServiceLost() { if (found[name] === service) remove(name) }
            override fun onServiceInfoCallbackUnregistered() = Unit
        }
        try {
            manager.registerServiceInfoCallback(service, app.mainExecutor, callback)
            observed[name] = { runCatching { manager.unregisterServiceInfoCallback(callback) }; Unit }
        } catch (_: RuntimeException) {
            queue.addLast(service)
            resolveNext()
        }
    }

    @Suppress("DEPRECATION")
    private fun resolveNext() {
        if (resolving || discovery == null) return
        val service = queue.removeFirstOrNull() ?: return
        resolving = true
        fun finish(info: NsdServiceInfo?) {
            handler.post {
                resolving = false
                if (info != null && found[service.serviceName] === service) update(service.serviceName, info)
                resolveNext()
            }
        }
        try {
            manager.resolveService(service, object : NsdManager.ResolveListener {
                override fun onServiceResolved(info: NsdServiceInfo) = finish(info)
                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) = finish(null)
            })
        } catch (_: RuntimeException) { finish(null) }
    }

    @Suppress("DEPRECATION")
    private fun update(name: String, info: NsdServiceInfo) {
        val addresses = if (Build.VERSION.SDK_INT >= 34) info.hostAddresses else listOfNotNull(info.host)
        val speaker = discoveredAirPlaySpeaker(name, info.port, addresses, info.attributes)
        if (speaker == null) resolved.remove(name) else resolved[name] = speaker
        publish()
    }

    private fun remove(name: String) { if (resolved.remove(name) != null) publish() }
    private fun publish() {
        speakers = resolved.values.distinctBy { it.id }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }

    private fun stop() {
        val listener = discovery
        discovery = null
        if (listener != null) runCatching { manager.stopServiceDiscovery(listener) }
        observed.values.forEach { it() }
        observed.clear()
        found.clear()
        queue.clear()
        resolved.clear()
        speakers = emptyList()
    }
}
