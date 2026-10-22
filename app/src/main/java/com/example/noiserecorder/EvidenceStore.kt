package com.example.noiserecorder

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File
import java.io.RandomAccessFile
import java.time.Instant

data class Evidence(val name: String, val started: String, val peak: Double, val duration: Double, val status: String)

class EvidenceStore(context: Context) : SQLiteOpenHelper(context, "evidence.db", null, 1) {
    val directory = File(context.filesDir, "recordings").apply { mkdirs() }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE recordings(name TEXT PRIMARY KEY, started TEXT NOT NULL, peak REAL NOT NULL, duration REAL NOT NULL DEFAULT 0, status TEXT NOT NULL, threshold REAL NOT NULL, offset REAL NOT NULL)")
        db.execSQL("CREATE TABLE triggers(id INTEGER PRIMARY KEY, time TEXT NOT NULL, level REAL NOT NULL, name TEXT NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    fun begin(name: String, time: String, level: Double, settings: MonitorSettings) {
        writableDatabase.insertOrThrow("recordings", null, ContentValues().apply { put("name", name); put("started", time); put("peak", level); put("status", "recording"); put("threshold", settings.threshold); put("offset", settings.offset) })
    }
    fun trigger(name: String, level: Double) { writableDatabase.insertOrThrow("triggers", null, ContentValues().apply { put("name", name); put("time", Instant.now().toString()); put("level", level) }) }
    fun finish(name: String, peak: Double, duration: Double, status: String) {
        writableDatabase.update("recordings", ContentValues().apply { put("peak", peak); put("duration", duration); put("status", status) }, "name=?", arrayOf(name))
    }
    fun list(): List<Evidence> = readableDatabase.rawQuery("SELECT name,started,peak,duration,status FROM recordings ORDER BY started DESC", null).use { c -> buildList { while(c.moveToNext()) add(Evidence(c.getString(0), c.getString(1), c.getDouble(2), c.getDouble(3), c.getString(4))) } }
    fun recover() {
        for (e in list().filter { it.status == "recording" }) {
            val file = File(directory, e.name)
            if (file.exists() && file.length() >= 44) { WavWriter.repair(file); finish(e.name, e.peak, (file.length() - 44) / 32000.0, "interrupted") }
            else finish(e.name, e.peak, 0.0, "missing")
        }
    }
    fun csv(table: String): String {
        require(table == "recordings" || table == "triggers")
        return readableDatabase.rawQuery("SELECT * FROM $table ORDER BY rowid", null).use { c ->
            fun quote(s: String) = "\"" + s.replace("\"", "\"\"") + "\""
            buildString { append('\uFEFF'); append(c.columnNames.joinToString(",", transform = ::quote)); append("\r\n"); while(c.moveToNext()) { append((0 until c.columnCount).joinToString(",") { quote(c.getString(it) ?: "") }); append("\r\n") } }
        }
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
