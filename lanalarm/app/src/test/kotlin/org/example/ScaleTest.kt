package org.example

import org.example.lanalarm.AppSettings
import org.example.lanalarm.ScaleCodec
import org.example.lanalarm.ScaleScanner
import org.example.lanalarm.ScaleSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the scale is actually saying, and what massalarme does about it.
 *
 * Byte 1 of the advertisement is a bitfield, and every behaviour the user can
 * see hangs off three of its bits. Reading them wrong means either an alarm that
 * never stops or one that stops before the user has stood still, and neither is
 * something to discover at 07:00.
 */
class ScaleFlagTest {

    /**
     * Real payloads. The first two came off the shipped weight log; the third is
     * the in-progress form captured in `xiaomi-exploration/`, which is what the
     * scale broadcasts continuously while someone is stepping on.
     */
    companion object {
        /** 0xa4: settled, no impedance, weight removed. Socks. */
        private const val SETTLED_WEIGHT_ONLY = "02a4b2070114161133fdffbc39"

        /** 0x26: settled, with impedance. Bare feet. */
        private const val SETTLED_BODY_FAT = "0226b207011416113332020039"

        /** 0x04: still climbing. */
        private const val IN_PROGRESS = "0204b2070101000b300000e439"
    }

    private fun decode(hex: String): ScaleCodec.ScaleReading {
        val bytes = ByteArray(hex.length / 2) {
            hex.substring(it * 2, it * 2 + 2).toInt(16).toByte()
        }
        return ScaleCodec.decode(bytes, 0L)!!
    }

    @Test
    fun `an in-progress advertisement carries a real weight but has not settled`() {
        val reading = decode(IN_PROGRESS)
        assertFalse("bit 5 is clear, so the scale is still deciding", reading.isStabilized)
        assertFalse(reading.hasImpedance)
        assertFalse(reading.weightRemoved)
        // 0x39e4 / 200. Showing this is the whole point of the live readout.
        assertEquals(74.1, reading.weightKg, 0.001)
    }

    @Test
    fun `the weight-only flag means settled, stepped off, and no body fat`() {
        val reading = decode(SETTLED_WEIGHT_ONLY)
        assertEquals(AppSettings.FLAG_WEIGHT_ONLY, reading.flag)
        assertTrue(reading.isStabilized)
        assertTrue("0x80 is what 'step off to finish' actually means", reading.weightRemoved)
        assertFalse(reading.hasImpedance)
        assertNull("65533 is the scale's way of saying it gave up", reading.impedance)
    }

    @Test
    fun `the body-fat flag means settled, still standing, with impedance`() {
        val reading = decode(SETTLED_BODY_FAT)
        assertEquals(AppSettings.FLAG_BODY_FAT, reading.flag)
        assertTrue(reading.isStabilized)
        assertTrue(reading.hasImpedance)
        assertFalse("the user has not stepped off yet", reading.weightRemoved)
        assertNotNull(reading.impedance)
    }

    @Test
    fun `only settled readings are worth recording`() {
        assertFalse(decode(IN_PROGRESS).isFinal)
        assertTrue(decode(SETTLED_WEIGHT_ONLY).isFinal)
        assertTrue(decode(SETTLED_BODY_FAT).isFinal)
    }

    @Test
    fun `a short payload is not a reading`() {
        assertNull(ScaleCodec.decode(ByteArray(6), 0L))
    }
}

/**
 * The three decisions a trip to the scale produces.
 *
 * These used to live inside [ScaleScanner] behind a `startScan()` call, which
 * meant they could not be tested without a Bluetooth radio — so the rule that
 * decides when a siren stops was never covered by anything.
 */
class ScaleSessionTest {

    private val live = mutableListOf<Double>()
    private val stopped = mutableListOf<Double>()
    private var completed: Pair<ScaleCodec.ScaleReading, Int>? = null

    private val listener = object : ScaleScanner.ScaleListener {
        override fun onLiveWeight(reading: ScaleCodec.ScaleReading) {
            live.add(reading.weightKg)
        }

        override fun onStableWeight(reading: ScaleCodec.ScaleReading) {
            stopped.add(reading.weightKg)
        }

        override fun onWeighInComplete(
            reading: ScaleCodec.ScaleReading,
            distinctMeasurements: Int
        ) {
            completed = reading to distinctMeasurements
        }
    }

    private fun session(stopFlag: Int = AppSettings.FLAG_WEIGHT_ONLY) =
        ScaleSession(stopFlag = stopFlag, minWeightKg = 30.0, listener = listener)

    /** A reading with a chosen flag and weight, distinct by its raw value. */
    private fun reading(
        flag: Int,
        weightKg: Double,
        impedance: Double? = null,
        raw: String = "%02x-%.2f".format(flag, weightKg)
    ) = ScaleCodec.ScaleReading(
        flag = flag,
        weightKg = weightKg,
        impedance = impedance,
        rawValue = raw,
        capturedAtMillis = 0L
    )

    @Test
    fun `every readable advertisement is shown, settled or not`() {
        val session = session()
        session.offer(reading(0x04, 40.0))
        session.offer(reading(0x04, 68.5))
        session.offer(reading(AppSettings.FLAG_WEIGHT_ONLY, 73.8))

        assertEquals(
            "the number has to climb on screen while the user steps on",
            listOf(40.0, 68.5, 73.8), live
        )
    }

    @Test
    fun `an unsettled reading does not open a session`() {
        val session = session()
        assertFalse(
            "the quiet timer must not be armed by the scale merely noticing a foot",
            session.offer(reading(0x04, 68.5))
        )
        assertFalse(session.isOpen())
    }

    @Test
    fun `a reading below the floor is ignored entirely`() {
        val session = session()
        assertFalse(session.offer(reading(AppSettings.FLAG_WEIGHT_ONLY, 4.2)))
        assertTrue("a cat is not a weigh-in, and not worth showing either", live.isEmpty())
    }

    @Test
    fun `the alarm stops on the first reading matching the stop flag`() {
        val session = session(stopFlag = AppSettings.FLAG_WEIGHT_ONLY)
        session.offer(reading(0x04, 70.0))
        session.offer(reading(AppSettings.FLAG_WEIGHT_ONLY, 73.8))
        session.offer(reading(AppSettings.FLAG_WEIGHT_ONLY, 73.9))

        assertEquals(
            "nobody should stand on a scale waiting for the session to settle",
            listOf(73.8), stopped
        )
    }

    @Test
    fun `in body-fat mode a weight-only reading does not stop the alarm`() {
        val session = session(stopFlag = AppSettings.FLAG_BODY_FAT)
        session.offer(reading(AppSettings.FLAG_WEIGHT_ONLY, 73.8))

        assertTrue(
            "the point of bare-feet mode is that socks do not get you out of it",
            stopped.isEmpty()
        )
    }

    @Test
    fun `in body-fat mode a weight-only reading is still recorded`() {
        // The regression this pins: filtering the session by the stop flag meant
        // a morning in socks with the app in body-fat mode threw the weigh-in
        // away entirely. What stops the alarm is a setting; what gets written
        // down is not.
        val session = session(stopFlag = AppSettings.FLAG_BODY_FAT)
        session.offer(reading(AppSettings.FLAG_WEIGHT_ONLY, 73.8))
        session.commit()

        assertEquals(73.8, completed!!.first.weightKg, 0.001)
    }

    @Test
    fun `the impedance reading wins over a later weight-only one`() {
        // Stepping off produces a final weight-only advertisement. Taking the
        // last reading blindly would discard the body-fat measurement the user
        // stood still for.
        val session = session(stopFlag = AppSettings.FLAG_BODY_FAT)
        session.offer(reading(AppSettings.FLAG_BODY_FAT, 73.8, impedance = 512.0))
        session.offer(reading(AppSettings.FLAG_WEIGHT_ONLY, 73.8))
        session.commit()

        assertEquals(512.0, completed!!.first.impedance!!, 0.001)
    }

    @Test
    fun `without impedance the last settled reading is the canonical one`() {
        val session = session()
        session.offer(reading(AppSettings.FLAG_WEIGHT_ONLY, 73.6))
        session.offer(reading(AppSettings.FLAG_WEIGHT_ONLY, 73.8))
        session.commit()

        assertEquals("the scale is still settling until the last payload",
            73.8, completed!!.first.weightKg, 0.001)
    }

    @Test
    fun `a rebroadcast payload is one measurement, not two`() {
        val session = session()
        session.offer(reading(AppSettings.FLAG_WEIGHT_ONLY, 73.8, raw = "same"))
        session.offer(reading(AppSettings.FLAG_WEIGHT_ONLY, 73.8, raw = "same"))
        session.commit()

        assertEquals(1, completed!!.second)
    }

    @Test
    fun `committing an empty session reports nothing`() {
        session().commit()
        assertNull(completed)
    }

    @Test
    fun `a committed session is closed and ready for the next trip`() {
        val session = session()
        session.offer(reading(AppSettings.FLAG_WEIGHT_ONLY, 73.8))
        assertTrue(session.isOpen())

        session.commit()
        assertFalse(session.isOpen())

        // And the alarm can be stopped again by the next trip, rather than the
        // announcement flag staying latched forever.
        session.offer(reading(AppSettings.FLAG_WEIGHT_ONLY, 74.0))
        assertEquals(listOf(73.8, 74.0), stopped)
    }
}
