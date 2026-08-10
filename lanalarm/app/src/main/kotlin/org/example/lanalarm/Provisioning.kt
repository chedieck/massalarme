package org.example.lanalarm

import org.json.JSONObject
import java.util.Locale

/**
 * Parsing of the pairing payload carried by the QR code from `make secret`.
 *
 * Kept pure and free of Android imports so it can be unit-tested — this is the
 * step that decides whether the app can be set up at all, and it failed silently
 * once already.
 *
 * Three accepted shapes, newest first:
 *
 *  1. `MA2:<SECRET>:<host>:<port>:<flag>:<min_kg>:<gap>` — the compact form.
 *     Everything stays inside QR's alphanumeric mode so the code is sparse
 *     enough to read off a terminal.
 *  2. A JSON object — an earlier revision. Far denser as a QR, hence replaced,
 *     but still understood.
 *  3. A bare hex string — the original secret-only code, from a PC that does not
 *     yet know how to share its address.
 */
object Provisioning {

    const val PREFIX = "MA2"

    data class Payload(
        val secret: String,
        val pcHost: String? = null,
        val pcPort: Int? = null,
        val stableFlag: Int? = null,
        val minWeightKg: Float? = null,
        val sessionGapSeconds: Int? = null
    )

    fun parse(scanned: String?): Payload? {
        val text = scanned?.trim().orEmpty()
        if (text.isEmpty()) return null

        return when {
            text.startsWith("$PREFIX:") -> parseCompact(text)
            text.startsWith("{") -> parseJson(text)
            isHexSecret(text) -> Payload(secret = text)
            else -> null
        }
    }

    private fun isHexSecret(text: String): Boolean =
        text.matches(Regex("[0-9a-fA-F]{32,}"))

    private fun parseCompact(text: String): Payload? {
        val parts = text.split(":")
        // prefix + secret, plus optional trailing fields.
        if (parts.size < 2) return null

        val secret = parts[1].trim()
        if (!isHexSecret(secret)) return null

        return Payload(
            // Stored lowercase whatever the QR said: the PC uppercases it purely
            // to stay in alphanumeric mode, but the shared secret it compares
            // against is lowercase hex.
            secret = secret.lowercase(Locale.US),
            pcHost = parts.getOrNull(2)?.trim()?.takeIf { it.isNotEmpty() },
            pcPort = parts.getOrNull(3)?.trim()?.toIntOrNull()?.takeIf { it in 1..65535 },
            stableFlag = parts.getOrNull(4)?.trim()?.toIntOrNull()?.takeIf { it in 0..255 },
            minWeightKg = parts.getOrNull(5)?.trim()?.toFloatOrNull()?.takeIf { it > 0f },
            sessionGapSeconds = parts.getOrNull(6)?.trim()?.toIntOrNull()?.takeIf { it > 0 }
        )
    }

    private fun parseJson(text: String): Payload? {
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return null
        val secret = json.optString("secret").trim()
        if (!isHexSecret(secret)) return null

        val scale = json.optJSONObject("scale")
        return Payload(
            secret = secret.lowercase(Locale.US),
            pcHost = json.optString("pc_host").takeIf { it.isNotBlank() },
            pcPort = json.optInt("pc_port", 0).takeIf { it in 1..65535 },
            stableFlag = scale?.optInt("stable_flag", -1)?.takeIf { it in 0..255 },
            minWeightKg = scale?.optDouble("min_weight_kg", -1.0)
                ?.takeIf { it > 0 }?.toFloat(),
            sessionGapSeconds = scale?.optInt("session_gap_seconds", 0)?.takeIf { it > 0 }
        )
    }
}
