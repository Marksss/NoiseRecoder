package com.example.noiserecorder

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.log10
import kotlin.math.pow

data class LevelSample(val time: Long, val duration: Long, val minimum: Double, val peak: Double, val mean: Double, val exceed: Long)
data class ExceedEvent(val start: Long, val end: Long, val duration: Long, val peak: Double)
data class MonitorSession(val id: Long, val start: Long, val end: Long, val threshold: Double, val offset: Double, val status: String)
data class SessionReport(val session: MonitorSession, val samples: List<LevelSample>, val events: List<ExceedEvent>) {
    val observedMs get() = samples.sumOf { it.duration }
    val exceededMs get() = samples.sumOf { it.exceed }
    val peak get() = samples.maxOfOrNull { it.peak }
    val mean: Double? get() = if (observedMs == 0L) null else 10 * log10(samples.sumOf { 10.0.pow(it.mean / 10.0) * it.duration } / observedMs)
}
data class ReportRecording(val evidence: Evidence, val sessionId: Long?, val threshold: Double, val offset: Double)
data class NoiseReport(val sessions: List<SessionReport>, val recordings: List<ReportRecording>, val generated: Long = System.currentTimeMillis(), val zone: ZoneId = ZoneId.systemDefault()) {
    val observedMs get() = sessions.sumOf { it.observedMs }
    val exceededMs get() = sessions.sumOf { it.exceededMs }
    val eventCount get() = sessions.sumOf { it.events.size }
    fun time(epoch: Long): String = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(zone).format(Instant.ofEpochMilli(epoch))
}
object ReportFormat {
    fun number(value: Double?) = if (value == null) "无数据" else String.format(Locale.US, "%.1f", value)
    fun seconds(ms: Long) = String.format(Locale.US, "%.1f 秒", ms / 1000.0)
    fun xml(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
}
