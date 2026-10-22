package com.example.noiserecorder

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** MS-CFB v3 container with a Package/Ole10Native stream. Audio is copied, never buffered whole. */
object OlePackage {
    fun write(file: File, output: OutputStream) {
        require(file.length() < 2_000_000_000L)
        val name = file.name.toByteArray(Charsets.UTF_8)
        val prefix = ByteArrayOutputStream().apply {
            le(2, 2); write(name); write(0); write(name); write(0)
            le(0, 2); le(3, 2); le(name.size + 1, 4); write(name); write(0); le(file.length().toInt(), 4)
        }.toByteArray()
        val nativeSize = maxOf(4096L, 4 + prefix.size + file.length() + 2)
        val nativeSectors = ((nativeSize + 511) / 512).toInt()
        val dataSectors = 1 + 8 + nativeSectors
        var fatCount = 1
        var difatCount: Int
        while (true) {
            difatCount = (maxOf(0, fatCount - 109) + 126) / 127
            val needed = (dataSectors + difatCount + fatCount + 127) / 128
            if (needed == fatCount) break
            fatCount = needed
        }
        val fatStart = dataSectors + difatCount
        val header = ByteBuffer.allocate(512).order(ByteOrder.LITTLE_ENDIAN)
        header.put(byteArrayOf(0xD0.toByte(),0xCF.toByte(),0x11,0xE0.toByte(),0xA1.toByte(),0xB1.toByte(),0x1A,0xE1.toByte()))
        header.position(24); header.putShort(0x003e); header.putShort(3); header.putShort(0xfffe.toShort()); header.putShort(9); header.putShort(6)
        header.position(40); header.putInt(0); header.putInt(fatCount); header.putInt(0); header.putInt(0); header.putInt(4096); header.putInt(-2); header.putInt(0)
        header.putInt(if (difatCount > 0) dataSectors else -2); header.putInt(difatCount)
        repeat(109) { header.putInt(if (it < fatCount) fatStart + it else -1) }
        output.write(header.array())
        val directory = ByteArray(512)
        fun entry(index: Int, label: String, type: Int, color: Int, child: Int, right: Int, start: Int, size: Long) {
            val b = ByteBuffer.wrap(directory).order(ByteOrder.LITTLE_ENDIAN)
            val at = index * 128
            b.position(at); b.put((label + '\u0000').toByteArray(Charsets.UTF_16LE))
            b.position(at + 64); b.putShort(((label.length + 1) * 2).toShort()); b.put(type.toByte()); b.put(color.toByte()); b.putInt(-1); b.putInt(right); b.putInt(child)
            if (type == 5) b.put(byteArrayOf(0x0c,0,3,0,0,0,0,0,0xc0.toByte(),0,0,0,0,0,0,0x46))
            b.position(at + 116); b.putInt(start); b.putLong(size)
        }
        entry(0,"Root Entry",5,1,1,-1,-2,0)
        entry(1,"\u0001Ole",2,1,-1,2,1,4096)
        entry(2,"\u0001Ole10Native",2,0,-1,-1,9,nativeSize)
        output.write(directory)
        val ole = byteArrayOf(1,0,0,2,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0)
        output.write(ole); output.zeros(4096L - ole.size)
        output.le((prefix.size + file.length() + 2).toInt(), 4)
        output.write(prefix); file.inputStream().use { it.copyTo(output) }; output.le(0, 2)
        output.zeros(nativeSectors * 512L - (4 + prefix.size + file.length() + 2))
        repeat(difatCount) { d ->
            repeat(127) { slot -> val f = 109 + d * 127 + slot; output.le(if (f < fatCount) fatStart + f else -1, 4) }
            output.le(if (d + 1 < difatCount) dataSectors + d + 1 else -2, 4)
        }
        val fat = IntArray(fatCount * 128) { -1 }
        fat[0] = -2
        for (s in 1..8) fat[s] = if (s == 8) -2 else s + 1
        for (s in 9 until dataSectors) fat[s] = if (s == dataSectors - 1) -2 else s + 1
        repeat(difatCount) { fat[dataSectors + it] = -4 }
        repeat(fatCount) { fat[fatStart + it] = -3 }
        val fatBytes=ByteBuffer.allocate(fat.size*4).order(ByteOrder.LITTLE_ENDIAN)
        fatBytes.asIntBuffer().put(fat); output.write(fatBytes.array())
    }
    private fun OutputStream.le(value: Int, count: Int) { repeat(count) { write((value ushr (it * 8)) and 255) } }
    private fun OutputStream.zeros(count: Long) { val zero = ByteArray(8192); var remaining = count; while (remaining > 0) { val n = minOf(zero.size.toLong(), remaining).toInt(); write(zero,0,n); remaining -= n } }
}
