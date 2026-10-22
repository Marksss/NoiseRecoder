package com.example.noiserecorder

import android.content.ContentValues
import kotlin.math.log10
import kotlin.math.pow

/** One-second energy buckets plus continuous 100 ms exceedance episodes, even without WAVs. */
class SessionLogger(private val store: EvidenceStore, settings: MonitorSettings, start: Long) {
    private val db = store.writableDatabase
    val id = db.insertOrThrow("sessions", null, ContentValues().apply { put("start_ms", start); put("end_ms", start); put("threshold", settings.threshold); put("offset", settings.offset); put("status", "monitoring") })
    private val limit = settings.threshold
    private var bucketStart = start
    private var end = start
    private var duration = 0L
    private var exceeded = 0L
    private var energy = 0.0
    private var minimum = Double.POSITIVE_INFINITY
    private var peak = Double.NEGATIVE_INFINITY
    private var eventId: Long? = null
    private var eventStart = start
    private var eventDuration = 0L
    private var eventPeak = Double.NEGATIVE_INFINITY
    fun add(time: Long, ms: Long, level: Double) {
        if (duration == 0L) bucketStart = time
        end = time + ms
        duration += ms; energy += 10.0.pow(level / 10) * ms
        minimum = minOf(minimum, level); peak = maxOf(peak, level)
        if (level >= limit) {
            exceeded += ms
            if (eventId == null) {
                eventStart = time; eventDuration = 0; eventPeak = level
                eventId = db.insertOrThrow("exceed_events", null, ContentValues().apply { put("session_id", id); put("start_ms", time); put("end_ms", end); put("duration_ms", 0); put("peak_db", level) })
            }
            eventDuration += ms; eventPeak = maxOf(eventPeak, level)
        } else if (eventId != null) { saveEvent(time); eventId = null }
        if (duration >= 1000) flush()
    }
    private fun saveEvent(at: Long) { eventId?.let { key -> db.update("exceed_events", ContentValues().apply { put("end_ms", at); put("duration_ms", eventDuration); put("peak_db", eventPeak) }, "id=?", arrayOf(key.toString())) } }
    private fun flush() {
        if (duration == 0L) return
        db.beginTransaction()
        try {
            db.insertOrThrow("levels", null, ContentValues().apply { put("session_id", id); put("time_ms", bucketStart); put("duration_ms", duration); put("min_db", minimum); put("peak_db", peak); put("mean_db", 10 * log10(energy / duration)); put("exceed_ms", exceeded) })
            saveEvent(end)
            db.execSQL("UPDATE sessions SET end_ms=? WHERE id=?", arrayOf(end, id))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        duration = 0; exceeded = 0; energy = 0.0; minimum = Double.POSITIVE_INFINITY; peak = Double.NEGATIVE_INFINITY
    }
    fun close(reason: String) {
        flush(); saveEvent(end)
        db.execSQL("UPDATE sessions SET end_ms=?, status=? WHERE id=?", arrayOf<Any>(end, reason, id))
    }
}
