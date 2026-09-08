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

    /**
     * Byte 1 of the advertisement is a bitfield, not an opaque tag.
     *
     * Naming the bits is what makes the live readout possible: an
     * advertisement that is not yet stabilised still carries a real weight,
     * and showing it is the difference between a number that climbs as you
     * step on and a screen that says nothing for four seconds.
     */
    private const val BIT_HAS_IMPEDANCE = 0x02
    private const val BIT_STABILIZED = 0x20
    private const val BIT_WEIGHT_REMOVED = 0x80

    data class ScaleReading(
        val flag: Int,
        val weightKg: Double,
        val impedance: Double?,
        val rawValue: String,
        val capturedAtMillis: Long
    ) {
        /** The scale has settled on this number; it is no longer climbing. */
        val isStabilized: Boolean get() = flag and BIT_STABILIZED != 0

        /** The body-composition measurement completed. Needs bare feet. */
        val hasImpedance: Boolean get() = flag and BIT_HAS_IMPEDANCE != 0

        /** The user has stepped off. The scale reports this once, at the end. */
        val weightRemoved: Boolean get() = flag and BIT_WEIGHT_REMOVED != 0

        /**
         * Worth writing down. An unstabilised reading is the scale thinking
         * out loud — fine to display, wrong to record as a weigh-in.
         */
        val isFinal: Boolean get() = isStabilized

        /**
         * Is this the reading the user is waiting for — the one that stops a
         * hard alarm?
         *
         * Asked of the *bits*, deliberately, rather than by comparing the whole
         * flag byte to a stored constant. Those are three independent bits, so
         * an equality test demands one exact combination of all three, and the
         * combination it demanded (`0xa4`) has the weight-removed bit set. That
         * meant standing on the scale broadcast `0x24` — settled, still stood on
         * it — which did not match, and the alarm kept going until the user gave
         * up and stepped off. Which is the one thing a person standing on a
         * scale in front of a siren will not think to try.
         *
         * @param requireBodyFat the bare-feet setting: hold out for the
         *   impedance measurement (`0x26`, or `0xa6` once they step off) rather
         *   than accepting a settled weight alone.
         */
        fun satisfiesAlarm(requireBodyFat: Boolean): Boolean =
            isStabilized && (!requireBodyFat || hasImpedance)
    }

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
