package org.example.lanalarm

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * "Am I at home?" — the gate on hard alarms.
 *
 * A hard alarm demands a weigh-in, and the scale lives in one bathroom. Away
 * from home there is no way to satisfy it, so demanding it would leave the user
 * trapped with a siren in a hotel room. Away from home a hard alarm degrades to
 * a soft one.
 *
 * Identity is the home network's SSID, plus its BSSID when known. The BSSID
 * (the access point's MAC) is the stronger check: an SSID called "wifi" in a
 * cafe should not unlock a hard alarm.
 */
object HomeNetwork {

    private const val TAG = "HomeNetwork"

    data class Status(
        val connected: Boolean,
        val ssid: String?,
        val bssid: String?,
        val isHome: Boolean,
        val reason: String
    )

    /**
     * Reading the SSID requires location permission and location services on.
     * Without it Android returns "<unknown ssid>", which must never be mistaken
     * for a match.
     */
    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

    fun currentSsid(context: Context): String? {
        if (!hasPermission(context)) return null

        @Suppress("DEPRECATION")
        val info = context.applicationContext
            .getSystemService(WifiManager::class.java)
            ?.connectionInfo ?: return null

        @Suppress("DEPRECATION")
        val raw = info.ssid ?: return null
        val ssid = raw.trim('"')
        return ssid.takeIf { it.isNotBlank() && it != WifiManager.UNKNOWN_SSID }
    }

    fun currentBssid(context: Context): String? {
        if (!hasPermission(context)) return null

        @Suppress("DEPRECATION")
        val bssid = context.applicationContext
            .getSystemService(WifiManager::class.java)
            ?.connectionInfo
            ?.bssid ?: return null

        // Android hands out this placeholder when it will not tell you.
        return bssid.takeIf { it.isNotBlank() && it != "02:00:00:00:00:00" }
    }

    /**
     * Is there a wifi link at all? The PC lives on the LAN, so anything that
     * talks to it is pointless without one — including retrying the websocket.
     */
    fun isWifiConnected(context: Context): Boolean {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
            ?: return false
        val network = connectivity.activeNetwork ?: return false
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    /**
     * Full assessment, with a reason string worth showing the user — "why did my
     * hard alarm let me off?" needs an answer.
     */
    fun status(context: Context): Status {
        val homeSsid = AppSettings.homeSsid(context)
        val homeBssid = AppSettings.homeBssid(context)

        if (homeSsid == null && homeBssid == null) {
            return Status(
                connected = isWifiConnected(context),
                ssid = null,
                bssid = null,
                // No home network configured yet: do not weaken the alarm, but
                // the settings screen nags about setting one.
                isHome = true,
                reason = "no home network set — hard alarms always apply"
            )
        }

        if (!hasPermission(context)) {
            return Status(
                connected = isWifiConnected(context),
                ssid = null,
                bssid = null,
                // Cannot prove we are away, so assume home and keep the alarm
                // hard. Failing open here would let a missing permission quietly
                // disable the whole point of the app.
                isHome = true,
                reason = "location permission missing — cannot read wifi, assuming home"
            )
        }

        if (!isWifiConnected(context)) {
            return Status(
                connected = false,
                ssid = null,
                bssid = null,
                isHome = false,
                reason = "not on wifi"
            )
        }

        val ssid = currentSsid(context)
        val bssid = currentBssid(context)

        val bssidMatches = homeBssid != null && bssid != null &&
            bssid.equals(homeBssid, ignoreCase = true)
        val ssidMatches = homeSsid != null && ssid != null && ssid == homeSsid

        // Either match is enough. A mesh or a dual-band router hands out a
        // different BSSID per access point, so requiring both would put the user
        // "away from home" in their own kitchen.
        val isHome = ssidMatches || bssidMatches
        val reason = when {
            bssidMatches -> "on home access point"
            isHome -> "on home wifi ($ssid)"
            ssid == null -> "wifi name unavailable"
            else -> "on '$ssid', not home"
        }

        Log.d(TAG, "Home check: $reason (isHome=$isHome)")
        return Status(true, ssid, bssid, isHome, reason)
    }

    fun isHome(context: Context): Boolean = status(context).isHome
}
