package app.droplink.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.NetworkInterface

object NetUtils {

    /** True if the phone is joined to any Wi-Fi network (internet not required). */
    @Suppress("DEPRECATION")
    fun isOnWifi(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        return cm.allNetworks.any { n -> cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
    }

    /** interface name → IPv4 address, for up, non-loopback, private (site-local) addresses. */
    fun privateIpv4ByInterface(): Map<String, String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
            .flatMap { nif ->
                nif.inetAddresses.toList()
                    .filterIsInstance<Inet4Address>()
                    .filter { it.isSiteLocalAddress }
                    .map { nif.name to it.hostAddress!! }
            }
            .toMap()
    }.getOrDefault(emptyMap())
}

/** Why a connection method isn't usable, mapped to a human-readable string resource in the UI. */
enum class LinkProblem {
    BLUETOOTH_OFF,
    /** Google Play services (needed for Nearby) is missing or outdated. */
    NEARBY_UNAVAILABLE,
    NEARBY_FAILED,
    NO_NETWORK,
    WIFI_OFF,
    LOCATION_OFF,
    PERMISSION_DENIED,
}

class LinkException(val problem: LinkProblem, detail: String? = null) : Exception(detail ?: problem.name)
