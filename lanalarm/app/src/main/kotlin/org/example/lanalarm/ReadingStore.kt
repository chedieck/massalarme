package org.example.lanalarm

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log

/**
 * The phone's own weigh-in log and outbound queue.
 *
 * The phone may be away from the PC for days. Readings are captured here first
 * and uploaded whenever the PC is reachable; nothing is ever dropped because an
 * upload failed, and nothing is deleted once uploaded — the PC holds a copy, not
 * the original.
 *
 * `synced_at IS NULL` is the queue. No separate queue table is needed.
 */
class ReadingStore(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val TAG = "ReadingStore"
        private const val DATABASE_NAME = "massalarme_readings.db"
        private const val DATABASE_VERSION = 1
        private const val TABLE = "readings"

        /** Matches the server's per-request cap. */
        const val MAX_BATCH = 500
    }

    data class Reading(
        val externalId: String,
        val capturedAtUtc: String,
        val weightKg: Double,
        val impedance: Double?,
        val rawValue: String?,
        val alarmName: String?,
        val measurements: Int
    )

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE (
                external_id   TEXT PRIMARY KEY,
                captured_at   TEXT NOT NULL,
                weight_kg     REAL NOT NULL,
                impedance     REAL,
                raw_value     TEXT,
                alarm_name    TEXT,
                measurements  INTEGER NOT NULL DEFAULT 1,
                created_at    INTEGER NOT NULL,
                synced_at     TEXT,
                attempts      INTEGER NOT NULL DEFAULT 0,
                last_error    TEXT
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX ix_readings_pending ON $TABLE(captured_at) WHERE synced_at IS NULL")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // No destructive migrations: this table is the phone's own record.
        Log.i(TAG, "Upgrading readings database $oldVersion -> $newVersion")
    }

    /**
     * Insert a weigh-in, ignoring one that is already known.
     *
     * Returns true when the row is new. Because [ScaleCodec.externalId] is a
     * pure function of the reading, re-recording the same weigh-in is a no-op
     * rather than a duplicate.
     */
    fun insert(reading: Reading): Boolean {
        val values = ContentValues().apply {
            put("external_id", reading.externalId)
            put("captured_at", reading.capturedAtUtc)
            put("weight_kg", reading.weightKg)
            reading.impedance?.let { put("impedance", it) }
            put("raw_value", reading.rawValue)
            put("alarm_name", reading.alarmName)
            put("measurements", reading.measurements)
            put("created_at", System.currentTimeMillis())
        }
        val rowId = writableDatabase.insertWithOnConflict(
            TABLE, null, values, SQLiteDatabase.CONFLICT_IGNORE
        )
        if (rowId == -1L) {
            Log.d(TAG, "Weigh-in ${reading.externalId} already recorded")
            return false
        }
        Log.i(TAG, "Recorded weigh-in ${reading.externalId} (%.2f kg)".format(reading.weightKg))
        return true
    }

    fun pending(limit: Int = MAX_BATCH): List<Reading> {
        val readings = mutableListOf<Reading>()
        readableDatabase.rawQuery(
            "SELECT external_id, captured_at, weight_kg, impedance, raw_value, alarm_name, " +
                "measurements FROM $TABLE WHERE synced_at IS NULL ORDER BY captured_at LIMIT ?",
            arrayOf(limit.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                readings.add(
                    Reading(
                        externalId = cursor.getString(0),
                        capturedAtUtc = cursor.getString(1),
                        weightKg = cursor.getDouble(2),
                        impedance = if (cursor.isNull(3)) null else cursor.getDouble(3),
                        rawValue = if (cursor.isNull(4)) null else cursor.getString(4),
                        alarmName = if (cursor.isNull(5)) null else cursor.getString(5),
                        measurements = cursor.getInt(6)
                    )
                )
            }
        }
        return readings
    }

    fun pendingCount(): Int {
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM $TABLE WHERE synced_at IS NULL", null
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    /** Called for accepted *and* duplicate readings: both mean the PC has it. */
    fun markSynced(externalIds: List<String>) {
        if (externalIds.isEmpty()) return
        val now = ScaleCodec.utcIso(System.currentTimeMillis())
        val db = writableDatabase
        db.beginTransaction()
        try {
            externalIds.forEach { externalId ->
                db.execSQL(
                    "UPDATE $TABLE SET synced_at = ?, last_error = NULL WHERE external_id = ?",
                    arrayOf(now, externalId)
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Give up on a reading the PC will never accept.
     *
     * The row stays — it is still the phone's own record — but it leaves the
     * queue so it is not retried forever.
     */
    fun markRejected(externalId: String, reason: String) {
        writableDatabase.execSQL(
            "UPDATE $TABLE SET synced_at = 'rejected', last_error = ? WHERE external_id = ?",
            arrayOf(reason.take(300), externalId)
        )
        Log.w(TAG, "Reading $externalId rejected by PC: $reason")
    }

    fun recordAttempt(externalIds: List<String>, error: String?) {
        if (externalIds.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            externalIds.forEach { externalId ->
                db.execSQL(
                    "UPDATE $TABLE SET attempts = attempts + 1, last_error = ? WHERE external_id = ?",
                    arrayOf(error?.take(300), externalId)
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun latest(limit: Int = 10): List<Reading> {
        val readings = mutableListOf<Reading>()
        readableDatabase.rawQuery(
            "SELECT external_id, captured_at, weight_kg, impedance, raw_value, alarm_name, " +
                "measurements FROM $TABLE ORDER BY captured_at DESC LIMIT ?",
            arrayOf(limit.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                readings.add(
                    Reading(
                        externalId = cursor.getString(0),
                        capturedAtUtc = cursor.getString(1),
                        weightKg = cursor.getDouble(2),
                        impedance = if (cursor.isNull(3)) null else cursor.getDouble(3),
                        rawValue = if (cursor.isNull(4)) null else cursor.getString(4),
                        alarmName = if (cursor.isNull(5)) null else cursor.getString(5),
                        measurements = cursor.getInt(6)
                    )
                )
            }
        }
        return readings
    }
}
