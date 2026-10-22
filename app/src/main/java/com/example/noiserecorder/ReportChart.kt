package com.example.noiserecorder

import android.graphics.*
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.ceil
import kotlin.math.floor

object ReportChart {
    fun draw(report: SessionReport, zone: ZoneId, file: File) {
        val bitmap = Bitmap.createBitmap(1200, 420, Bitmap.Config.ARGB_8888)
        try {
            val c = Canvas(bitmap); c.drawColor(Color.WHITE)
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 23f; color = Color.DKGRAY }
            val left = 90f; val right = 1160f; val top = 55f; val bottom = 340f
            val low = floor(minOf(report.samples.minOfOrNull { it.minimum } ?: 0.0, report.session.threshold) / 10) * 10 - 10
            val high = ceil(maxOf(report.peak ?: 60.0, report.session.threshold) / 10) * 10 + 10
            val start = report.session.start; val end = maxOf(start + 1000, report.session.end)
            fun x(time: Long) = left + ((time - start).toDouble() / (end - start) * (right - left)).toFloat()
            fun y(db: Double) = bottom - ((db - low) / (high - low) * (bottom - top)).toFloat()
            c.drawText("估算声级 dB SPL（未计权）", left, 30f, p)
            repeat(6) { i ->
                val value = low + (high - low) * i / 5
                p.color = Color.rgb(225,230,236); p.strokeWidth = 1f; c.drawLine(left,y(value),right,y(value),p)
                p.color = Color.DKGRAY; c.drawText(ReportFormat.number(value), 6f,y(value)+8,p)
            }
            val formatter = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(zone)
            repeat(6) { i -> val t = start + (end - start) * i / 5; p.textAlign = when(i) { 0 -> Paint.Align.LEFT; 5 -> Paint.Align.RIGHT; else -> Paint.Align.CENTER }; c.drawText(formatter.format(Instant.ofEpochMilli(t)), x(t),bottom+36,p) }
            p.textAlign = Paint.Align.LEFT
            p.color = Color.rgb(195,55,55); p.strokeWidth = 3f; p.pathEffect = DashPathEffect(floatArrayOf(12f,8f),0f)
            c.drawLine(left,y(report.session.threshold),right,y(report.session.threshold),p); p.pathEffect = null
            var previous: LevelSample? = null
            // Display min/max envelopes per horizontal pixel so long reports preserve short peaks.
            report.samples.groupBy { x(it.time).toInt() }.forEach { (pixel, bucket) ->
                p.color = Color.rgb(157,190,218); p.strokeWidth = 2f
                c.drawLine(pixel.toFloat(),y(bucket.minOf { it.minimum }),pixel.toFloat(),y(bucket.maxOf { it.peak }),p)
            }
            p.color = Color.rgb(28,100,160); p.strokeWidth = 2f
            report.samples.forEach { current -> previous?.let { old -> if (current.time - old.time in 0..2500) c.drawLine(x(old.time),y(old.mean),x(current.time),y(current.mean),p) }; previous = current }
            p.textSize = 21f; c.drawText("蓝线：每秒能量平均；浅蓝：最小–最大；红虚线：阈值；空白：无样本",left,402f,p)
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }
    fun audioIcon(file: File) {
        val b = Bitmap.createBitmap(480,100,Bitmap.Config.ARGB_8888)
        try { val c=Canvas(b); c.drawColor(Color.rgb(230,239,248)); val p=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.rgb(22,75,122); textSize=32f }; c.drawText("双击打开原始音频",35f,62f,p); file.outputStream().use { b.compress(Bitmap.CompressFormat.PNG,100,it) } }
        finally { b.recycle() }
    }
}
