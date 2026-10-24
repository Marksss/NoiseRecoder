package com.example.noiserecorder

import kotlin.math.log10
import kotlin.math.sqrt

enum class MonitorMode {
    IMMEDIATE, DAILY;
    fun shouldListen(minute: Int, start: Int, end: Int) =
        this == IMMEDIATE || AudioLogic.inWindow(minute, start, end)
    companion object {
        fun fromValue(value: String?) = values().firstOrNull { it.name == value } ?: DAILY
    }
}

object AudioLogic {
    const val RATE = 16000
    fun inWindow(minute: Int, start: Int, end: Int): Boolean =
        if (start < end) minute in start until end else minute >= start || minute < end
    fun dbfs(samples: ShortArray, count: Int): Double {
        if (count == 0) return -120.0
        var sum = 0.0
        for (i in 0 until count) { val n = samples[i].toDouble() / 32768.0; sum += n * n }
        return (20 * log10(sqrt(sum / count).coerceAtLeast(0.000001))).coerceAtLeast(-120.0)
    }
}

/** PCM byte ring; snapshot is chronological and contains only actually captured bytes. */
class PreRoll(private val capacity: Int = AudioLogic.RATE * 2 * 5) {
    private val data = ByteArray(capacity)
    private var position = 0
    private var size = 0
    fun add(bytes: ByteArray) { for (b in bytes) { data[position] = b; position = (position + 1) % capacity; size = minOf(size + 1, capacity) } }
    fun snapshot(): ByteArray = ByteArray(size) { data[(position - size + capacity + it) % capacity] }
    fun clear() { position = 0; size = 0 }
}

/** All deadlines use elapsed time, unaffected by wall-clock corrections. */
class RecordingTail {
    private var deadline = 0L
    fun extend(now: Long) { deadline = now + 60000L }
    fun expired(now: Long) = now >= deadline
}
