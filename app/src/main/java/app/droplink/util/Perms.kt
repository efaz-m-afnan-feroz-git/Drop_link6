package app.droplink.util

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat

/** Which runtime permissions each feature needs on which API level. */
object Perms {

    /**
     * Google Nearby Connections: Bluetooth (12+: "Nearby devices"), Wi-Fi (13+: "Nearby Wi-Fi
     * devices") and, on Android 8–12, location (Android's rule for Bluetooth/Wi-Fi scanning).
     */
    fun nearby(): List<String> {
        val out = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            out += Manifest.permission.BLUETOOTH_SCAN
            out += Manifest.permission.BLUETOOTH_ADVERTISE
            out += Manifest.permission.BLUETOOTH_CONNECT
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            out += Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            out += Manifest.permission.ACCESS_FINE_LOCATION
            out += Manifest.permission.ACCESS_COARSE_LOCATION
        }
        return out
    }

    fun notifications(): List<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        listOf(Manifest.permission.POST_NOTIFICATIONS)
    } else {
        emptyList()
    }

    /** Android 8–9 write received files to the public Download folder. */
    @Suppress("DEPRECATION")
    fun legacyStorage(): List<String> = if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
        listOf(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    } else {
        emptyList()
    }

    fun granted(context: Context, permissions: List<String>): Boolean = permissions.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    /** On 8–12, Bluetooth/Wi-Fi scanning also needs the location switch on. */
    fun needsLocationServices(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU

    fun locationServicesOn(context: Context): Boolean {
        if (!needsLocationServices()) return true
        val lm = context.getSystemService(LocationManager::class.java) ?: return false
        return LocationManagerCompat.isLocationEnabled(lm)
    }

    fun nearbyReady(context: Context): Boolean = granted(context, nearby()) && locationServicesOn(context)

    @Suppress("MissingPermission")
    fun bluetoothOn(context: Context): Boolean = runCatching {
        context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true
    }.getOrDefault(true)

    fun wifiOn(context: Context): Boolean = runCatching {
        context.applicationContext.getSystemService(WifiManager::class.java)?.isWifiEnabled == true
    }.getOrDefault(true)
}
