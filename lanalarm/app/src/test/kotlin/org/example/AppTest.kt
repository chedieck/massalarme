package org.example

import org.example.lanalarm.AlarmSchedule
import org.example.lanalarm.ScaleCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * The parts of the phone that must agree with the PC, or with themselves.
 *
 * The identity tests are the important ones: `external_id` is computed
 * independently in Kotlin and in Python, and if the two ever disagree the same
 * weigh-in becomes two points on the user's chart. The expected values below
 * were produced by the Python implementation in `store.make_external_id`.
 */
class ScaleScannerTest {

    companion object {
        /** A real advertisement from the shipped database. */
        private const val RAW = "02a4b2070114161133fdffbc39"

        /** 2026-08-09T07:12:03Z */
        private const val MILLIS = 1786259523000L
    }

    @Test
    fun `external id matches the python implementation exactly`() {
        assertEquals(
            "2026-08-09T07:12:03Z-67e08daf",
            ScaleCodec.externalId(MILLIS, RAW, 73.9)
        )
    }

    @Test
    fun `external id falls back to the weight when there is no raw payload`() {
        assertEquals(
            "2026-08-09T07:12:03Z-1c57f039",
            ScaleCodec.externalId(MILLIS, null, 73.9)
        )
    }

    @Test
    fun `utc iso matches the python rendering`() {
        assertEquals("2026-08-09T07:12:03Z", ScaleCodec.utcIso(MILLIS))
    }

    @Test
    fun `external id ignores sub-second jitter so retries do not duplicate`() {
        assertEquals(
            ScaleCodec.externalId(MILLIS, RAW, 73.9),
            ScaleCodec.externalId(MILLIS + 987, RAW, 73.9)
        )
    }

    @Test
    fun `the same payload on different days gets different ids`() {
        val aDayLater = MILLIS + 86_400_000L
        assertNotEquals(
            ScaleCodec.externalId(MILLIS, RAW, 72.3),
            ScaleCodec.externalId(aDayLater, RAW, 72.3)
        )
    }

    @Test
    fun `decoding an advertisement matches the python byte offsets`() {
        val payload = RAW.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

        val reading = ScaleCodec.decode(payload, MILLIS)!!

        assertEquals(0xa4, reading.flag)
        assertEquals(73.9, reading.weightKg, 0.001)
        // 65533 is the scale's "no impedance measured" sentinel.
        assertNull(reading.impedance)
        assertEquals(RAW, reading.rawValue)
    }

    @Test
    fun `impedance is kept when the scale actually measured it`() {
        val payload = "0226b207020e0a1e1f4102be37".chunked(2)
            .map { it.toInt(16).toByte() }.toByteArray()

        val reading = ScaleCodec.decode(payload, MILLIS)!!

        assertEquals(71.35, reading.weightKg, 0.001)
        assertEquals(577.0, reading.impedance!!, 0.001)
    }

    @Test
    fun `a truncated advertisement is rejected rather than misread`() {
        assertNull(ScaleCodec.decode(byteArrayOf(0x02, 0x26, 0x00), MILLIS))
    }
}

/**
 * Occurrence maths. The phone now schedules its own alarms, so these rules
 * decide whether the user wakes up.
 */
class AlarmScheduleTest {

    private fun alarm(
        time: String = "07:30",
        days: List<String> = emptyList(),
        date: String? = null,
        type: String? = null,
        enabled: Boolean = true,
        deleted: Boolean = false,
        kind: String = AlarmSchedule.KIND_HARD
    ) = AlarmSchedule.Alarm(
        id = "test",
        name = "Test",
        time = time,
        days = days,
        date = date,
        type = type,
        enabled = enabled,
        deleted = deleted,
        kind = kind,
        updatedAt = 0L
    )

    /** Monday 2026-08-10, 06:00 local. */
    private fun mondayMorning(): Long = Calendar.getInstance().apply {
        timeZone = TimeZone.getDefault()
        set(2026, Calendar.AUGUST, 10, 6, 0, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    @Test
    fun `parses both time formats`() {
        assertEquals(Triple(7, 30, 0), AlarmSchedule.parseTime("07:30"))
        assertEquals(Triple(7, 30, 15), AlarmSchedule.parseTime("07:30:15"))
    }

    @Test
    fun `rejects malformed and out-of-range times`() {
        assertNull(AlarmSchedule.parseTime("25:00"))
        assertNull(AlarmSchedule.parseTime("07:61"))
        assertNull(AlarmSchedule.parseTime("nonsense"))
        assertNull(AlarmSchedule.parseTime("7"))
    }

    @Test
    fun `a weekly alarm fires later the same day when the time has not passed`() {
        val now = mondayMorning()
        val next = AlarmSchedule.nextOccurrence(alarm(days = listOf("monday")), now)!!

        val calendar = Calendar.getInstance().apply { timeInMillis = next }
        assertEquals(Calendar.MONDAY, calendar.get(Calendar.DAY_OF_WEEK))
        assertEquals(10, calendar.get(Calendar.DAY_OF_MONTH))
        assertEquals(7, calendar.get(Calendar.HOUR_OF_DAY))
    }

    @Test
    fun `a weekly alarm skips to next week once today's time has passed`() {
        val now = mondayMorning() + 3 * 3600_000L // 09:00, past 07:30
        val next = AlarmSchedule.nextOccurrence(alarm(days = listOf("monday")), now)!!

        val calendar = Calendar.getInstance().apply { timeInMillis = next }
        assertEquals(Calendar.MONDAY, calendar.get(Calendar.DAY_OF_WEEK))
        assertEquals(17, calendar.get(Calendar.DAY_OF_MONTH))
    }

    @Test
    fun `the soonest of several days wins`() {
        val now = mondayMorning()
        val next = AlarmSchedule.nextOccurrence(
            alarm(days = listOf("friday", "tuesday", "sunday")), now
        )!!

        val calendar = Calendar.getInstance().apply { timeInMillis = next }
        assertEquals(Calendar.TUESDAY, calendar.get(Calendar.DAY_OF_WEEK))
    }

    @Test
    fun `a disabled or deleted alarm never fires`() {
        val now = mondayMorning()
        assertNull(AlarmSchedule.nextOccurrence(alarm(days = listOf("monday"), enabled = false), now))
        assertNull(AlarmSchedule.nextOccurrence(alarm(days = listOf("monday"), deleted = true), now))
    }

    @Test
    fun `a dated alarm in the past has been and gone`() {
        val now = mondayMorning()
        assertNull(AlarmSchedule.nextOccurrence(alarm(date = "01-01-2020"), now))
    }

    @Test
    fun `a dated alarm in the future fires on that date`() {
        val now = mondayMorning()
        val next = AlarmSchedule.nextOccurrence(alarm(date = "25-12-2026", time = "08:00"), now)!!

        val calendar = Calendar.getInstance().apply { timeInMillis = next }
        assertEquals(2026, calendar.get(Calendar.YEAR))
        assertEquals(Calendar.DECEMBER, calendar.get(Calendar.MONTH))
        assertEquals(25, calendar.get(Calendar.DAY_OF_MONTH))
    }

    @Test
    fun `a one-shot alarm fires at the next occurrence of that clock time`() {
        val now = mondayMorning()
        val next = AlarmSchedule.nextOccurrence(alarm(type = "next", time = "07:30"), now)!!

        assertTrue(next > now)
        assertTrue(next - now < 24 * 3600_000L)
    }

    @Test
    fun `alarms default to hard so an existing schedule keeps demanding the scale`() {
        assertTrue(alarm().isHard)
        assertTrue(alarm(kind = "anything-unrecognised").isHard)
        assertTrue(!alarm(kind = AlarmSchedule.KIND_SOFT).isHard)
    }

    @Test
    fun `the soonest alarm across a list is chosen`() {
        val now = mondayMorning()
        val alarms = listOf(
            alarm(time = "09:00", days = listOf("monday")),
            alarm(time = "07:00", days = listOf("monday")),
            alarm(time = "08:00", days = listOf("monday"))
        )

        val (winner, _) = AlarmSchedule.nextAlarm(alarms, now)!!
        assertEquals("07:00", winner.time)
    }

    @Test
    fun `an empty schedule yields nothing`() {
        assertNull(AlarmSchedule.nextAlarm(emptyList(), mondayMorning()))
    }
}
