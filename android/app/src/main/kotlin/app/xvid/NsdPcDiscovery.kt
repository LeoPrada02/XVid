package app.xvid

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import app.xvid.core.FoundPc
import app.xvid.core.PcDiscovery
import java.net.Inet4Address
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * Finds the PCs on the Wi-Fi with Android's network service discovery. Each PC announces the
 * DNS-SD service `_xvid._tcp` with its id in the TXT record (see app/discovery.py on the PC).
 * Blocks for a few seconds, so it must not run on the main thread.
 */
class NsdPcDiscovery(context: Context) : PcDiscovery {
    private val nsd = context.getSystemService(NsdManager::class.java)

    override fun find(): List<FoundPc> {
        val services = CopyOnWriteArrayList<NsdServiceInfo>()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(service: NsdServiceInfo) {
                services += service
            }

            override fun onServiceLost(service: NsdServiceInfo) = Unit
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }
        nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        try {
            Thread.sleep(SEARCH_MILLIS)
        } finally {
            runCatching { nsd.stopServiceDiscovery(listener) } // throws if discovery never started
        }
        return services.distinctBy { it.serviceName }.mapNotNull(::resolve)
    }

    /** The service's id and IPv4 address. One at a time: before Android 14, a second resolve fails. */
    private fun resolve(service: NsdServiceInfo): FoundPc? {
        val resolved = CompletableFuture<NsdServiceInfo?>()
        val listener = object : NsdManager.ResolveListener {
            override fun onServiceResolved(info: NsdServiceInfo) {
                resolved.complete(info)
            }

            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                resolved.complete(null)
            }
        }
        @Suppress("DEPRECATION") // its replacement needs Android 14
        nsd.resolveService(service, listener)
        val info = try {
            resolved.get(RESOLVE_MILLIS, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            if (Build.VERSION.SDK_INT >= 34) runCatching { nsd.stopServiceResolution(listener) }
            null
        } ?: return null
        val id = info.attributes["id"]?.toString(Charsets.UTF_8)?.takeIf { it.isNotBlank() } ?: return null
        @Suppress("DEPRECATION")
        val addresses = if (Build.VERSION.SDK_INT >= 34) info.hostAddresses else listOfNotNull(info.host)
        val address = addresses.firstOrNull { it is Inet4Address }?.hostAddress ?: return null
        return FoundPc(id, "https://$address:${info.port}")
    }

    private companion object {
        const val SERVICE_TYPE = "_xvid._tcp"
        const val SEARCH_MILLIS = 3_000L
        const val RESOLVE_MILLIS = 3_000L
    }
}
