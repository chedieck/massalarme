package org.example.lanalarm

import org.json.JSONObject

/**
 * Parsing the ontoplano connection payload behind the QR code.
 *
 * The QR used to carry a shared secret and a PC's LAN address. There is no PC in
 * the path any more, so it now carries what the phone actually needs to reach
 * ontoplano: a base URL and a bearer token. Typing a 40-character token into a
 * phone keyboard is the kind of setup step people abandon halfway.
 *
 * Kept pure and free of Android imports so it can be unit-tested — this is the
 * step that decides whether the app can be set up at all, and it failed silently
 * once already.
 *
 * Three accepted shapes:
 *
 *  1. `massalarme://connect?base=<url-encoded>&token=<token>` — the URI form,
 *     which is also what a "connect this device" link can open directly.
 *  2. A JSON object `{"base_url": "…", "token": "…"}`.
 *  3. A bare token, for when the server address is already configured.
 */
object Provisioning {

    const val SCHEME = "massalarme://connect"

    data class Payload(val baseUrl: String?, val token: String)

    fun parse(scanned: String?): Payload? {
        val text = scanned?.trim().orEmpty()
        if (text.isEmpty()) return null

        return when {
            text.startsWith(SCHEME) -> parseUri(text)
            text.startsWith("{") -> parseJson(text)
            looksLikeToken(text) -> Payload(baseUrl = null, token = text)
            else -> null
        }
    }

    /**
     * Loose on purpose. A token format is the server's business and will
     * outlive any pattern hardcoded here; all this has to rule out is a QR code
     * from a bus ticket. Whitespace is the real tell — tokens do not contain it.
     */
    private fun looksLikeToken(text: String): Boolean =
        text.length in 16..512 && text.none { it.isWhitespace() } && !text.contains("://")

    private fun parseUri(text: String): Payload? {
        val query = text.substringAfter('?', "")
        if (query.isEmpty()) return null

        val fields = query.split("&").mapNotNull { pair ->
            val key = pair.substringBefore('=', "")
            val value = pair.substringAfter('=', "")
            if (key.isEmpty() || value.isEmpty()) null else key to decode(value)
        }.toMap()

        val token = fields["token"]?.trim().orEmpty()
        if (token.isEmpty()) return null
        return Payload(baseUrl = normaliseBase(fields["base"] ?: fields["base_url"]), token = token)
    }

    private fun parseJson(text: String): Payload? {
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return null
        val token = json.optString("token").trim()
        if (token.isEmpty()) return null
        return Payload(
            baseUrl = normaliseBase(json.optString("base_url").takeIf { it.isNotBlank() }),
            token = token
        )
    }

    /**
     * A trailing slash here becomes a double slash in every request path, and
     * some gateways answer that with a 404 that looks like a missing stream.
     */
    fun normaliseBase(raw: String?): String? {
        val text = raw?.trim()?.trimEnd('/').orEmpty()
        if (text.isEmpty()) return null
        return if (text.contains("://")) text else "https://$text"
    }

    /** Percent-decoding, enough for a URL inside a query parameter. */
    private fun decode(value: String): String =
        runCatching { java.net.URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
}
