package com.example.noiserecorder

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ReportLogicTest {
    @get:Rule val temp=TemporaryFolder()
    @Test fun averagesEnergyAndOnlyCountsCapturedTime() {
        val s=MonitorSession(1,0,100000,45.0,90.0,"user_stop")
        val r=SessionReport(s,listOf(LevelSample(0,1000,0.0,0.0,0.0,0),LevelSample(99000,1000,10.0,10.0,10.0,500)),emptyList())
        assertEquals(2000,r.observedMs); assertEquals(500,r.exceededMs)
        assertEquals(7.4036,r.mean!!,0.001)
        assertEquals(10.0,r.peak!!,0.001)
    }
    @Test fun emptySessionDoesNotClaimQuietMeasurements() {
        val r=SessionReport(MonitorSession(1,0,0,45.0,90.0,"interrupted"),emptyList(),emptyList())
        assertNull(r.mean); assertNull(r.peak); assertEquals(0,r.observedMs)
        assertEquals("&lt;&amp;&quot;&apos;",ReportFormat.xml("<&\"'"))
    }
    @Test fun smallOleAttachmentRetainsOriginalBytes() { verifyOle(100) }
    @Test fun largeOleAttachmentUsesDifatAndRetainsOriginalBytes() { verifyOle(8*1024*1024) }
    private fun verifyOle(size:Int) {
        val original=ByteArray(size) { (it*13).toByte() }
        val input=temp.newFile("test.wav").apply { writeBytes(original) }
        val output=temp.newFile("test.bin")
        output.outputStream().buffered().use { OlePackage.write(input,it) }
        val bytes=output.readBytes(); val b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertArrayEquals(byteArrayOf(0xd0.toByte(),0xcf.toByte(),0x11,0xe0.toByte(),0xa1.toByte(),0xb1.toByte(),0x1a,0xe1.toByte()),bytes.copyOfRange(0,8))
        assertEquals(3,b.getShort(26).toInt()); assertEquals(9,b.getShort(30).toInt())
        val fatCount=b.getInt(44)
        val fatSectors=mutableListOf<Int>()
        repeat(109) { val id=b.getInt(76+it*4); if(id>=0) fatSectors.add(id) }
        var difat=b.getInt(68)
        var visited=0
        while(difat>=0) { val at=(difat+1)*512; repeat(127) { val id=b.getInt(at+it*4); if(id>=0) fatSectors.add(id) }; difat=b.getInt(at+508); check(++visited<1000) }
        assertEquals(fatCount,fatSectors.size)
        if(size>7*1024*1024) assertTrue(b.getInt(72)>0)
        val fat=fatSectors.flatMap { id -> (0 until 128).map { b.getInt((id+1)*512+it*4) } }
        fun stream(start:Int,length:Int):ByteArray {
            var id=start; val data=ByteArrayOutputStream(); val seen=hashSetOf<Int>()
            while(id>=0) { check(seen.add(id)); data.write(bytes,(id+1)*512,512); id=fat[id] }
            assertEquals(-2,id)
            return data.toByteArray().copyOf(length)
        }
        val directory=stream(b.getInt(48),512)
        val d=ByteBuffer.wrap(directory).order(ByteOrder.LITTLE_ENDIAN)
        val entry=(0..3).first { i -> val n=d.getShort(i*128+64).toInt(); n>0 && String(directory,i*128,n-2,Charsets.UTF_16LE)=="\u0001Ole10Native" }
        val native=stream(d.getInt(entry*128+116),d.getLong(entry*128+120).toInt())
        val n=ByteBuffer.wrap(native).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(2,n.getShort(4).toInt())
        var cursor=6
        repeat(2) { while(native[cursor++].toInt()!=0) { } }
        cursor+=4
        val commandLength=n.getInt(cursor); cursor+=4+commandLength
        val payloadLength=n.getInt(cursor); cursor+=4
        assertEquals(size,payloadLength)
        assertArrayEquals(original,native.copyOfRange(cursor,cursor+payloadLength))
    }
}
