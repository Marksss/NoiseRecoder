package com.example.noiserecorder

import android.content.Context

data class MonitorSettings(val start: Int = 150, val end: Int = 360, val threshold: Double = 45.0, val offset: Double = 90.0) {
    companion object {
        fun load(context: Context): MonitorSettings {
            val p = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            return MonitorSettings(p.getInt("start", 150), p.getInt("end", 360), p.getFloat("threshold", 45f).toDouble(), p.getFloat("offset", 90f).toDouble())
        }
    }
    fun save(context: Context) {
        check(context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
            .putInt("start", start).putInt("end", end)
            .putFloat("threshold", threshold.toFloat()).putFloat("offset", offset.toFloat())
            .commit()) { "设置写入失败，请检查存储空间" }
    }
}
