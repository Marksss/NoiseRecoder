package com.example.noiserecorder

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File
import java.io.RandomAccessFile
import java.time.Instant

data class Evidence(val name: String, val started: String, val peak: Double, val duration: Double, val status: String, val sessionId: Long? = null)

class EvidenceStore(context: Context) : SQLiteOpenHelper(context, "evidence.db", null, 2) {
    val directory = File(context.filesDir, "recordings").apply { mkdirs() }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE recordings(name TEXT PRIMARY KEY, started TEXT NOT NULL, peak REAL NOT NULL, duration REAL NOT NULL DEFAULT 0, status TEXT NOT NULL, threshold REAL NOT NULL, offset REAL NOT NULL, session_id INTEGER, deleted_at INTEGER)")
        db.execSQL("CREATE TABLE triggers(id INTEGER PRIMARY KEY, time TEXT NOT NULL, level REAL NOT NULL, name TEXT NOT NULL)")
        createReportTables(db)
    }
    private fun createReportTables(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE sessions(id INTEGER PRIMARY KEY, start_ms INTEGER NOT NULL, end_ms INTEGER NOT NULL, threshold REAL NOT NULL, offset REAL NOT NULL, status TEXT NOT NULL)")
        db.execSQL("CREATE TABLE levels(id INTEGER PRIMARY KEY, session_id INTEGER NOT NULL, time_ms INTEGER NOT NULL, duration_ms INTEGER NOT NULL, min_db REAL NOT NULL, peak_db REAL NOT NULL, mean_db REAL NOT NULL, exceed_ms INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE exceed_events(id INTEGER PRIMARY KEY, session_id INTEGER NOT NULL, start_ms INTEGER NOT NULL, end_ms INTEGER NOT NULL, duration_ms INTEGER NOT NULL, peak_db REAL NOT NULL)")
        db.execSQL("CREATE INDEX levels_session ON levels(session_id,time_ms)")
        db.execSQL("CREATE INDEX events_session ON exceed_events(session_id,start_ms)")
        db.execSQL("CREATE INDEX recordings_session ON recordings(session_id)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE recordings ADD COLUMN session_id INTEGER")
            db.execSQL("ALTER TABLE recordings ADD COLUMN deleted_at INTEGER")
            createReportTables(db)
        }
    }
    fun begin(name: String, time: String, level: Double, settings: MonitorSettings, sessionId: Long? = null) {
        writableDatabase.insertOrThrow("recordings", null, ContentValues().apply { put("name", name); put("started", time); put("peak", level); put("status", "recording"); put("threshold", settings.threshold); put("offset", settings.offset); if (sessionId != null) put("session_id", sessionId) })
    }
    fun trigger(name: String, level: Double) { writableDatabase.insertOrThrow("triggers", null, ContentValues().apply { put("name", name); put("time", Instant.now().toString()); put("level", level) }) }
    fun finish(name: String, peak: Double, duration: Double, status: String) {
        writableDatabase.update("recordings", ContentValues().apply { put("peak", peak); put("duration", duration); put("status", status) }, "name=?", arrayOf(name))
    }
    fun list(): List<Evidence> = readableDatabase.rawQuery("SELECT name,started,peak,duration,status,session_id FROM recordings WHERE status NOT IN ('deleted','deleting') ORDER BY started DESC", null).use { c -> buildList { while(c.moveToNext()) add(Evidence(c.getString(0), c.getString(1), c.getDouble(2), c.getDouble(3), c.getString(4), if(c.isNull(5)) null else c.getLong(5))) } }
    /** A durable session marker is written before touching audio; DB removal commits together. */
    fun deleteSession(id: Long) {
        val key=arrayOf(id.toString())
        val old=readableDatabase.rawQuery("SELECT status FROM sessions WHERE id=?",key).use { c -> check(c.moveToFirst()) { "监听时段不存在" }; c.getString(0) }
        check(old!="monitoring") { "请先停止该时段的值守" }
        val rows=readableDatabase.rawQuery("SELECT name,status FROM recordings WHERE session_id=?",key).use { c -> buildList { while(c.moveToNext()) add(c.getString(0) to c.getString(1)) } }
        check(rows.none { it.second=="recording" }) { "该时段仍有正在录制的音频，请先停止值守" }
        writableDatabase.execSQL("UPDATE sessions SET status='deleting' WHERE id=?",arrayOf(id))
        try {
            rows.forEach { deleteRecording(it.first) }
            val db=writableDatabase
            db.beginTransaction()
            try {
                db.execSQL("DELETE FROM triggers WHERE name IN (SELECT name FROM recordings WHERE session_id=?)",arrayOf(id))
                db.delete("recordings","session_id=?",key)
                db.delete("levels","session_id=?",key)
                db.delete("exceed_events","session_id=?",key)
                db.delete("sessions","id=?",key)
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        } catch(e: Exception) {
            // An explicit I/O error leaves already deleted WAVs marked deleted and analytics intact.
            writableDatabase.execSQL("UPDATE sessions SET status=? WHERE id=?",arrayOf<Any>(if(old=="deleting") "interrupted" else old,id))
            throw e
        }
    }
    /** Analytics remain intact. A durable intent lets recovery finish an interrupted deletion. */
    fun deleteRecording(name: String) {
        require(File(directory, name).canonicalFile.parentFile == directory.canonicalFile && name.endsWith(".wav"))
        val old = readableDatabase.rawQuery("SELECT status FROM recordings WHERE name=?", arrayOf(name)).use { c -> check(c.moveToFirst()) { "录音不存在" }; c.getString(0) }
        check(old != "recording") { "请先停止值守" }
        writableDatabase.execSQL("UPDATE recordings SET status='deleting', deleted_at=? WHERE name=?", arrayOf<Any>(System.currentTimeMillis(), name))
        val file = File(directory, name)
        if (file.exists() && !file.delete()) {
            writableDatabase.execSQL("UPDATE recordings SET status=?, deleted_at=NULL WHERE name=?", arrayOf(old, name))
            error("无法删除录音，请稍后重试")
        }
        writableDatabase.execSQL("UPDATE recordings SET status='deleted' WHERE name=?", arrayOf(name))
    }
    fun recover() {
        val deletingSessions=readableDatabase.rawQuery("SELECT id FROM sessions WHERE status='deleting'",null).use { c -> buildList { while(c.moveToNext()) add(c.getLong(0)) } }
        deletingSessions.forEach { deleteSession(it) }
        readableDatabase.rawQuery("SELECT name FROM recordings WHERE status='deleting'", null).use { c -> while(c.moveToNext()) deleteRecording(c.getString(0)) }
        writableDatabase.execSQL("UPDATE sessions SET status='interrupted' WHERE status='monitoring'")
        writableDatabase.execSQL("DELETE FROM exceed_events WHERE duration_ms=0")
        for (e in list().filter { it.status == "recording" }) {
            val file = File(directory, e.name)
            if (file.exists() && file.length() >= 44) { WavWriter.repair(file); finish(e.name, e.peak, (file.length() - 44) / 32000.0, "interrupted") }
            else finish(e.name, e.peak, 0.0, "missing")
        }
    }
    fun csv(table: String): String {
        require(table in setOf("recordings", "triggers", "sessions", "levels", "exceed_events"))
        return readableDatabase.rawQuery("SELECT * FROM $table ORDER BY rowid", null).use { c ->
            fun quote(s: String) = "\"" + s.replace("\"", "\"\"") + "\""
            buildString { append('\uFEFF'); append(c.columnNames.joinToString(",", transform = ::quote)); append("\r\n"); while(c.moveToNext()) { append((0 until c.columnCount).joinToString(",") { quote(c.getString(it) ?: "") }); append("\r\n") } }
        }
    }
    fun sessions(): List<MonitorSession> = readableDatabase.rawQuery("SELECT id,start_ms,end_ms,threshold,offset,status FROM sessions ORDER BY start_ms DESC,id DESC", null).use { c -> buildList { while(c.moveToNext()) add(MonitorSession(c.getLong(0),c.getLong(1),c.getLong(2),c.getDouble(3),c.getDouble(4),c.getString(5))) } }
    fun report(ids: Set<Long>, legacy: Boolean): NoiseReport {
        require(ids.size <= 31) { "一次报告最多选择 31 个监听时段" }
        val sessions = sessions().filter { it.id in ids }.map { s ->
            val levels = readableDatabase.rawQuery("SELECT time_ms,duration_ms,min_db,peak_db,mean_db,exceed_ms FROM levels WHERE session_id=? ORDER BY time_ms,id", arrayOf(s.id.toString())).use { c -> buildList { while(c.moveToNext()) add(LevelSample(c.getLong(0),c.getLong(1),c.getDouble(2),c.getDouble(3),c.getDouble(4),c.getLong(5))) } }
            val events = readableDatabase.rawQuery("SELECT start_ms,end_ms,duration_ms,peak_db FROM exceed_events WHERE session_id=? ORDER BY start_ms,id", arrayOf(s.id.toString())).use { c -> buildList { while(c.moveToNext()) add(ExceedEvent(c.getLong(0),c.getLong(1),c.getLong(2),c.getDouble(3))) } }
            SessionReport(s, levels, events)
        }.sortedBy { it.session.start }
        val recordings = readableDatabase.rawQuery("SELECT name,started,peak,duration,status,session_id,threshold,offset FROM recordings ORDER BY started,name", null).use { c -> buildList {
            while(c.moveToNext()) {
                val id = if (c.isNull(5)) null else c.getLong(5)
                if ((id == null && legacy) || id in ids) add(ReportRecording(Evidence(c.getString(0), c.getString(1),c.getDouble(2),c.getDouble(3),c.getString(4)),id,c.getDouble(6),c.getDouble(7)))
            }
        } }
        return NoiseReport(sessions, recordings)
    }
}

class WavWriter(val file: File) : AutoCloseable {
    private val output = RandomAccessFile(file, "rw")
    var bytes: Long = 0; private set
    init { output.setLength(0); output.write(ByteArray(44)) }
    fun write(data: ByteArray) { output.write(data); bytes += data.size }
    override fun close() { try { header(output, bytes) } finally { output.close() } }
    companion object {
        fun repair(file: File) { RandomAccessFile(file, "rw").use { header(it, (it.length() - 44).coerceAtLeast(0)) } }
        private fun header(out: RandomAccessFile, size: Long) {
            fun le(value: Long, count: Int) { repeat(count) { out.write(((value shr (8 * it)) and 255).toInt()) } }
            out.seek(0); out.writeBytes("RIFF"); le(size + 36, 4); out.writeBytes("WAVEfmt "); le(16, 4); le(1, 2); le(1, 2); le(16000, 4); le(32000, 4); le(2, 2); le(16, 2); out.writeBytes("data"); le(size, 4)
        }
    }
}
