package org.example.lanalarm

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONObject

/**
 * Every persisted setting in one place.
 *
 * The phone is now the autonomous half of massalarme: it schedules its own
 * alarms, listens to the scale itself, and reports upwards. That means it needs
 * the scale decoding parameters and the PC's address, not just a shared secret.
 * All of it arrives in one QR code from `make secret`.
 */
object AppSettings {

    const val PREFS_NAME = "massalarme_prefs"

    // Provisioning
    const val KEY_SECRET = "shared_secret"
    const val KEY_PC_IP = "pc_ip"
    const val KEY_PC_PORT = "pc_port"

    // Alarm data
    const val KEY_ALARMS = "alarms_json"
    const val KEY_LAST_SYNC = "last_sync"

    // Scale decoding
    const val KEY_SCALE_NAME = "scale_name"
    const val KEY_SCALE_STABLE_FLAG = "scale_stable_flag"
    const val KEY_SCALE_MIN_WEIGHT = "scale_min_weight"
    const val KEY_SCALE_SESSION_GAP = "scale_session_gap"

    // Home network — a hard alarm only demands the scale where the scale is.
    const val KEY_HOME_SSID = "home_ssid"
    const val KEY_HOME_BSSID = "home_bssid"

    // Upload state, for the status surface
    const val KEY_LAST_UPLOAD_OK = "last_upload_ok"
    const val KEY_LAST_UPLOAD_ERROR = "last_upload_error"
    const val KEY_LAST_WEIGHT = "last_weight"
    const val KEY_LAST_WEIGHT_AT = "last_weight_at"

    const val DEFAULT_PC_PORT = 8888
    const val DEFAULT_SCALE_NAME = "MIBFS"

    /** 0xa4 — weight stabilised. 0xa6 additionally requires a barefoot impedance read. */
    const val DEFAULT_STABLE_FLAG = 0xa4
    const val DEFAULT_MIN_WEIGHT_KG = 30f

    /** Advertisements closer together than this are one trip to the scale. */
    const val DEFAULT_SESSION_GAP_SECONDS = 90

    private const val TAG = "Settings"

    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun secret(context: Context): String? =
        prefs(context).getString(KEY_SECRET, null)?.takeIf { it.isNotBlank() }

    fun pcBaseUrl(context: Context): String? {
        val prefs = prefs(context)
        val host = prefs.getString(KEY_PC_IP, null)?.takeIf { it.isNotBlank() } ?: return null
        val port = prefs.getInt(KEY_PC_PORT, DEFAULT_PC_PORT)
        return "http://$host:$port"
    }

    fun scaleName(context: Context): String =
        prefs(context).getString(KEY_SCALE_NAME, DEFAULT_SCALE_NAME) ?: DEFAULT_SCALE_NAME

    fun stableFlag(context: Context): Int =
        prefs(context).getInt(KEY_SCALE_STABLE_FLAG, DEFAULT_STABLE_FLAG)

    fun minWeightKg(context: Context): Float =
        prefs(context).getFloat(KEY_SCALE_MIN_WEIGHT, DEFAULT_MIN_WEIGHT_KG)

    fun sessionGapSeconds(context: Context): Int =
        prefs(context).getInt(KEY_SCALE_SESSION_GAP, DEFAULT_SESSION_GAP_SECONDS)

    fun homeSsid(context: Context): String? =
        prefs(context).getString(KEY_HOME_SSID, null)?.takeIf { it.isNotBlank() }

    fun homeBssid(context: Context): String? =
        prefs(context).getString(KEY_HOME_BSSID, null)?.takeIf { it.isNotBlank() }

    fun setHomeNetwork(context: Context, ssid: String?, bssid: String?) {
        prefs(context).edit()
            .putString(KEY_HOME_SSID, ssid)
            .putString(KEY_HOME_BSSID, bssid)
            .apply()
    }

    /**
     * Apply a scanned provisioning payload.
     *
     * Accepts both shapes: the v2 JSON object produced by `make secret`, and a
     * bare 64-char hex secret from an older PC that only knew how to share that.
     * Returns false if the text is neither.
     */
    fun applyProvisioning(context: Context, scanned: String): Boolean {
        val text = scanned.trim()
        if (text.isEmpty()) return false

        val editor = prefs(context).edit()

        val json = runCatching { JSONObject(text) }.getOrNull()
        if (json == null) {
            // Legacy QR: the secret and nothing else.
            if (!text.matches(Regex("[0-9a-fA-F]{32,}"))) {
                Log.w(TAG, "Scanned text is neither JSON nor a hex secret")
                return false
            }
            editor.putString(KEY_SECRET, text).apply()
            Log.i(TAG, "Applied legacy secret-only provisioning")
            return true
        }

        val secret = json.optString("secret").takeIf { it.isNotBlank() } ?: return false
        editor.putString(KEY_SECRET, secret)

        json.optString("pc_host").takeIf { it.isNotBlank() }?.let {
            editor.putString(KEY_PC_IP, it)
        }
        json.optInt("pc_port", 0).takeIf { it > 0 }?.let {
            editor.putInt(KEY_PC_PORT, it)
        }

        json.optJSONObject("scale")?.let { scale ->
            scale.optString("name").takeIf { it.isNotBlank() }?.let {
                editor.putString(KEY_SCALE_NAME, it)
            }
            scale.optInt("stable_flag", -1).takeIf { it >= 0 }?.let {
                editor.putInt(KEY_SCALE_STABLE_FLAG, it)
            }
            scale.optDouble("min_weight_kg", -1.0).takeIf { it > 0 }?.let {
                editor.putFloat(KEY_SCALE_MIN_WEIGHT, it.toFloat())
            }
            scale.optInt("session_gap_seconds", 0).takeIf { it > 0 }?.let {
                editor.putInt(KEY_SCALE_SESSION_GAP, it)
            }
        }

        editor.apply()
        Log.i(TAG, "Applied provisioning payload v${json.optInt("v", 1)}")
        return true
    }
}
