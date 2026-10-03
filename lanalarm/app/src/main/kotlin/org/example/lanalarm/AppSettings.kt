package org.example.lanalarm

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * Every persisted setting in one place.
 *
 * The phone is the whole of massalarme now. It holds the schedule, listens to
 * the scale, rings, and reports upwards to ontoplano over the internet. There is
 * no PC in the path, so there is no PC address, no LAN secret and no pairing
 * state here any more — what is left is the user's own configuration.
 */
object AppSettings {

    const val PREFS_NAME = "massalarme_prefs"

    // Alarm data
    const val KEY_ALARMS = "alarms_json"
    const val KEY_LAST_SYNC = "last_sync"

    // Scale decoding
    const val KEY_SCALE_STABLE_FLAG = "scale_stable_flag"
    const val KEY_SCALE_MIN_WEIGHT = "scale_min_weight"
    const val KEY_SCALE_SESSION_GAP = "scale_session_gap"

    // Home network — a hard alarm only demands the scale where the scale is.
    const val KEY_HOME_SSID = "home_ssid"
    const val KEY_HOME_BSSID = "home_bssid"

    // ontoplano, reached directly from the phone.
    const val KEY_ONTOPLANO_ENABLED = "ontoplano_enabled"
    const val KEY_ONTOPLANO_BASE_URL = "ontoplano_base_url"
    const val KEY_ONTOPLANO_TOKEN = "ontoplano_token"
    const val KEY_ONTOPLANO_PATTERN = "ontoplano_pattern"
    const val KEY_ONTOPLANO_KIND = "ontoplano_kind"

    // Dismissal
    const val KEY_PASSPHRASE = "dismiss_passphrase"
    const val KEY_PASSPHRASE_ENABLED = "dismiss_passphrase_enabled"
    const val KEY_SNOOZE_MINUTES = "snooze_minutes"

    // Sync state, for the status surface
    const val KEY_LAST_UPLOAD_OK = "last_upload_ok"
    const val KEY_LAST_UPLOAD_ERROR = "last_upload_error"
    const val KEY_LAST_WEIGHT = "last_weight"
    const val KEY_LAST_WEIGHT_AT = "last_weight_at"

    /**
     * Which of the two stop conditions a hard alarm uses.
     *
     * These are stored values, not a byte to compare an advertisement against.
     * Byte 1 of the Xiaomi advertisement is a bitfield — bit 5 "settled", bit 1
     * "impedance came with it", bit 7 "the weight has been taken off" — so the
     * scale emits four finished states, not two:
     *
     *  - `0x24` settled, still stood on it
     *  - `0xa4` settled, stepped off
     *  - `0x26` settled with impedance, still stood on it
     *  - `0xa6` settled with impedance, stepped off
     *
     * Testing a reading by equality against one of these demands an exact
     * combination of all three bits, and `0xa4` includes "stepped off" — which
     * is why standing on the scale used to leave a hard alarm ringing. The test
     * belongs on the bits: see [ScaleCodec.ScaleReading.satisfiesAlarm].
     */
    const val FLAG_WEIGHT_ONLY = 0xa4
    const val FLAG_BODY_FAT = 0x26

    const val DEFAULT_STABLE_FLAG = FLAG_WEIGHT_ONLY
    const val DEFAULT_MIN_WEIGHT_KG = 30f

    /** Advertisements closer together than this are one trip to the scale. */
    const val DEFAULT_SESSION_GAP_SECONDS = 90

    /**
     * The escape hatch for a hard alarm, kept long enough that typing it is a
     * real decision rather than a reflex.
     *
     * This used to be a constant compiled into the app, which meant it was the
     * same for everybody and could not be changed by the person it was supposed
     * to inconvenience. It is a default now, not a rule.
     */
    const val DEFAULT_PASSPHRASE =
        "Act as if what you do makes a difference. It does."

    const val DEFAULT_SNOOZE_MINUTES = 9

    private const val TAG = "Settings"

    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ─── Scale ───────────────────────────────────────────────────────

    fun stableFlag(context: Context): Int =
        prefs(context).getInt(KEY_SCALE_STABLE_FLAG, DEFAULT_STABLE_FLAG)

    fun setStableFlag(context: Context, flag: Int) {
        prefs(context).edit().putInt(KEY_SCALE_STABLE_FLAG, flag).apply()
    }

    fun requiresBodyFat(context: Context): Boolean = stableFlag(context) == FLAG_BODY_FAT

    fun minWeightKg(context: Context): Float =
        prefs(context).getFloat(KEY_SCALE_MIN_WEIGHT, DEFAULT_MIN_WEIGHT_KG)

    fun sessionGapSeconds(context: Context): Int =
        prefs(context).getInt(KEY_SCALE_SESSION_GAP, DEFAULT_SESSION_GAP_SECONDS)

    // ─── Dismissal ───────────────────────────────────────────────────

    fun passphrase(context: Context): String =
        prefs(context).getString(KEY_PASSPHRASE, null)?.takeIf { it.isNotBlank() }
            ?: DEFAULT_PASSPHRASE

    /**
     * Whether the passphrase escape hatch exists at all.
     *
     * On by default: being locked out by a flat scale battery is worse than the
     * occasional cheat. Someone who wants no way out but the scale can say so.
     */
    fun passphraseEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_PASSPHRASE_ENABLED, true)

    fun setPassphrase(context: Context, phrase: String?, enabled: Boolean) {
        prefs(context).edit()
            .putString(KEY_PASSPHRASE, phrase?.trim()?.takeIf { it.isNotBlank() })
            .putBoolean(KEY_PASSPHRASE_ENABLED, enabled)
            .apply()
    }

    fun snoozeMinutes(context: Context): Int =
        prefs(context).getInt(KEY_SNOOZE_MINUTES, DEFAULT_SNOOZE_MINUTES)

    /** Zero turns snooze off entirely; the upper bound keeps it a snooze. */
    fun setSnoozeMinutes(context: Context, minutes: Int) {
        prefs(context).edit().putInt(KEY_SNOOZE_MINUTES, minutes.coerceIn(0, 60)).apply()
    }

    fun snoozeEnabled(context: Context): Boolean = snoozeMinutes(context) > 0

    // ─── Home network ────────────────────────────────────────────────

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

    // ─── ontoplano ───────────────────────────────────────────────────

    fun ontoplanoEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ONTOPLANO_ENABLED, false)

    fun ontoplanoBaseUrl(context: Context): String? =
        prefs(context).getString(KEY_ONTOPLANO_BASE_URL, null)?.takeIf { it.isNotBlank() }

    fun ontoplanoToken(context: Context): String? =
        SecretStore.get(context, KEY_ONTOPLANO_TOKEN)

    /** Did the token actually make it into the keystore? Surfaced, not hidden. */
    fun ontoplanoTokenProtected(context: Context): Boolean =
        SecretStore.isProtected(context, KEY_ONTOPLANO_TOKEN)

    fun setOntoplano(context: Context, baseUrl: String?, token: String?, enabled: Boolean) {
        prefs(context).edit()
            .putString(KEY_ONTOPLANO_BASE_URL, Provisioning.normaliseBase(baseUrl))
            .putBoolean(KEY_ONTOPLANO_ENABLED, enabled)
            .apply()
        // Only overwrite the token when one was actually supplied. Saving the
        // server address alone is an ordinary thing to want, and must not
        // require re-pasting a token that is already stored.
        token?.let { SecretStore.put(context, KEY_ONTOPLANO_TOKEN, it) }
    }

    fun setOntoplanoEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ONTOPLANO_ENABLED, enabled).apply()
    }

    /**
     * A ready client, or null when sync is off or half-configured.
     *
     * Returning null rather than a client that fails on first use is what lets
     * every caller treat "no ontoplano" as an ordinary state instead of an error
     * to report — which it is: this app works with no account at all.
     */
    fun ontoplanoClient(context: Context): Ontoplano? {
        if (!ontoplanoEnabled(context)) return null
        val baseUrl = ontoplanoBaseUrl(context) ?: return null
        val token = ontoplanoToken(context) ?: return null
        return Ontoplano(Ontoplano.Config(baseUrl = baseUrl, token = token))
    }

    fun ontoplanoPattern(context: Context): String =
        prefs(context).getString(KEY_ONTOPLANO_PATTERN, "") ?: ""

    fun ontoplanoKind(context: Context): String =
        prefs(context).getString(KEY_ONTOPLANO_KIND, AlarmSchedule.KIND_SOFT)
            ?: AlarmSchedule.KIND_SOFT

    fun setOntoplanoRule(context: Context, pattern: String, kind: String) {
        prefs(context).edit()
            .putString(KEY_ONTOPLANO_PATTERN, pattern.trim())
            .putString(KEY_ONTOPLANO_KIND, kind)
            .apply()
    }

    // ─── Provisioning ────────────────────────────────────────────────

    /**
     * Apply a scanned connection payload. See [Provisioning] for the accepted
     * shapes. Returns false if the text is none of them.
     */
    fun applyProvisioning(context: Context, scanned: String): Boolean {
        val payload = Provisioning.parse(scanned)
        if (payload == null) {
            Log.w(TAG, "Scanned text is not a recognised connection payload")
            return false
        }

        val baseUrl = payload.baseUrl ?: ontoplanoBaseUrl(context)
        if (baseUrl == null) {
            Log.w(TAG, "Scanned a token but no server address is configured")
            return false
        }

        setOntoplano(context, baseUrl, payload.token, enabled = true)
        Log.i(TAG, "Connected to ontoplano at $baseUrl")
        return true
    }
}
