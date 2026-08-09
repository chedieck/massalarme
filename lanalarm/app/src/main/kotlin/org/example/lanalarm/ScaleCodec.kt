package org.example.lanalarm

import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Decoding a scale advertisement, and deriving a reading's identity.
 *
 * Deliberately free of any Android import. Everything here is a pure function of
 * its arguments, which is what lets it be unit-tested on the JVM and checked
 * byte-for-byte against the Python implementation on the PC — the two must never
 * disagree, or one weigh-in becomes two points on the user's chart.
 */
object ScaleCodec {

    /** Xiaomi body-composition payload: ctrl, flags, date(7), impedance(2), weight(2). */
    private const val PAYLOAD_LENGTH = 13

    /** The scale reports this when it never got an impedance reading. */
    private const val IMPEDANCE_UNSET = 65533.0

    data class ScaleReading(
        val flag: Int,
        val weightKg: Double,
        val impedance: Double?,
        val rawValue: String,
        val capturedAtMillis: Long
    )

    private fun utcFormatter(): SimpleDateFormat =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

    fun utcIso(millis: Long): String = utcFormatter().format(Date(millis))

    fun toHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    /**
     * Stable id derived purely from the reading.
     *
     * Must stay byte-identical to `store.make_external_id` on the PC: same
     * reading, same id, forever, across reinstalls and device changes. Never a
     * random uuid — a response lost to a dropped connection would then duplicate
     * the point.
     *
     * Truncating to whole seconds is deliberate: a retry that re-derives the id
     * milliseconds later must produce the same string. The raw payload alone is
     * not enough, because the scale's embedded clock is often unset and two
     * weigh-ins on different days can be byte-identical.
     */
    fun externalId(capturedAtMillis: Long, rawValue: String?, weightKg: Double): String {
        val fingerprint = if (!rawValue.isNullOrEmpty()) {
            rawValue
        } else {
            String.format(Locale.US, "%.4f", weightKg)
        }
        val digest = MessageDigest.getInstance("SHA-1")
            .digest(fingerprint.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(8)
        return "${utcIso(capturedAtMillis)}-$digest"
    }

    /**
     * Decode one advertisement, or null if it is not a usable measurement.
     *
     * Byte offsets match `alarm_manager.wait_for_weight` exactly.
     */
    fun decode(payload: ByteArray, capturedAtMillis: Long): ScaleReading? {
        if (payload.size < PAYLOAD_LENGTH) return null

        val size = payload.size
        val rawWeight = (payload[size - 2].toInt() and 0xFF) or
            ((payload[size - 1].toInt() and 0xFF) shl 8)
        val rawImpedance = (payload[size - 4].toInt() and 0xFF) or
            ((payload[size - 3].toInt() and 0xFF) shl 8)

        val impedance = rawImpedance.toDouble()

        return ScaleReading(
            flag = payload[1].toInt() and 0xFF,
            weightKg = rawWeight / 200.0,
            impedance = if (impedance == IMPEDANCE_UNSET) null else impedance,
            rawValue = toHex(payload),
            capturedAtMillis = capturedAtMillis
        )
    }
}
