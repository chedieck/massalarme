package org.example.lanalarm

/**
 * One trip to the scale, assembled from a stream of advertisements.
 *
 * Deliberately free of Android imports and of any clock or handler: it is fed
 * readings and reports back what they mean. That is what makes the three
 * decisions in here testable, and they are the ones worth testing — they decide
 * when a siren stops and which number goes on the chart:
 *
 *  1. **What is shown.** Every readable advertisement, settled or not. The scale
 *     talks the whole time someone is standing on it.
 *  2. **What stops the alarm.** A reading matching the user's chosen stop flag,
 *     and only the first one — nobody should have to stand there waiting for the
 *     session to settle.
 *  3. **What is recorded.** The settled readings, whatever the stop flag is.
 *     These used to be the same rule, which meant a morning in socks with the
 *     app set to body-fat mode threw the weigh-in away entirely.
 */
class ScaleSession(
    private val requireBodyFat: Boolean,
    private val minWeightKg: Double,
    private val listener: ScaleScanner.ScaleListener
) {

    private val readings = mutableListOf<ScaleCodec.ScaleReading>()
    private var announced = false

    fun isOpen(): Boolean = readings.isNotEmpty()

    /**
     * Take one advertisement.
     *
     * @return true when the session is live and the quiet timer should be
     *   restarted — the trip ends when the scale stops talking, not on a fixed
     *   deadline.
     */
    fun offer(reading: ScaleCodec.ScaleReading): Boolean {
        // Below the floor is the scale weighing a cat, a bag, or itself.
        if (reading.weightKg <= minWeightKg) return false

        listener.onLiveWeight(reading)

        // An unsettled reading is the scale thinking out loud: fine to display,
        // wrong to record, and not a reason to keep the session open.
        if (!reading.isFinal) return false

        // Identical payloads are the same measurement rebroadcast — the scale's
        // own clock is embedded, so equal bytes never mean two distinct readings.
        if (readings.none { it.rawValue == reading.rawValue }) {
            readings.add(reading)
        }

        if (!announced && reading.satisfiesAlarm(requireBodyFat)) {
            announced = true
            listener.onStableWeight(reading)
        }

        return true
    }

    /** The scale has gone quiet. Report the reading worth keeping, if any. */
    fun commit() {
        if (readings.isEmpty()) {
            announced = false
            return
        }

        // The last distinct payload is normally the settled one — except when an
        // earlier reading carried impedance and the last did not. Stepping off
        // produces a final weight-only advertisement, and preferring it would
        // silently discard the body-fat measurement the user stood still for.
        val canonical = readings.lastOrNull { it.hasImpedance } ?: readings.last()
        val distinct = readings.size

        readings.clear()
        announced = false

        listener.onWeighInComplete(canonical, distinct)
    }
}
