package com.example.noiserecorder

import org.junit.Assert.*
import org.junit.Test

class AudioLogicTest {
    @Test fun immediateModeIgnoresScheduledStartAndEnd() {
        for(minute in listOf(0,149,150,359,360,720,1439))
            assertTrue(MonitorMode.IMMEDIATE.shouldListen(minute,150,360))
    }
    @Test fun dailyModeReleasesOutsideWindowAndRepeatsAcrossMidnight() {
        assertFalse(MonitorMode.DAILY.shouldListen(1379,1380,360))
        assertTrue(MonitorMode.DAILY.shouldListen(1380,1380,360))
        assertTrue(MonitorMode.DAILY.shouldListen(0,1380,360))
        assertTrue(MonitorMode.DAILY.shouldListen(359,1380,360))
        assertFalse(MonitorMode.DAILY.shouldListen(360,1380,360))
        assertTrue(MonitorMode.DAILY.shouldListen(1380,1380,360))
    }
    @Test fun missingOrUnknownModeKeepsDailyCompatibility() {
        assertEquals(MonitorMode.DAILY,MonitorMode.fromValue(null))
        assertEquals(MonitorMode.DAILY,MonitorMode.fromValue("unknown"))
        assertEquals(MonitorMode.IMMEDIATE,MonitorMode.fromValue("IMMEDIATE"))
    }
    @Test fun scheduleHasExclusiveEndAndSupportsMidnight() {
        assertTrue(AudioLogic.inWindow(150, 150, 360))
        assertFalse(AudioLogic.inWindow(360, 150, 360))
        assertTrue(AudioLogic.inWindow(1430, 1380, 360))
        assertTrue(AudioLogic.inWindow(30, 1380, 360))
        assertFalse(AudioLogic.inWindow(720, 1380, 360))
    }
    @Test fun preRollPreservesChronologicalOrderAndNeverPadsWithSilence() {
        val ring = PreRoll(6)
        ring.add(byteArrayOf(1, 2)); assertArrayEquals(byteArrayOf(1, 2), ring.snapshot())
        ring.add(byteArrayOf(3, 4, 5, 6, 7, 8)); assertArrayEquals(byteArrayOf(3, 4, 5, 6, 7, 8), ring.snapshot())
        ring.clear(); assertEquals(0, ring.snapshot().size)
    }
    @Test fun rmsUsesAmplitudeRatherThanSamplePeak() {
        assertEquals(-6.0206, AudioLogic.dbfs(ShortArray(1600) { 16384 }, 1600), 0.001)
        assertEquals(-120.0, AudioLogic.dbfs(ShortArray(1600), 1600), 0.001)
    }
    @Test fun repeatedTriggerRetainsSixtySecondsAfterLastExceedance() {
        val tail = RecordingTail()
        tail.extend(1000)
        assertFalse(tail.expired(60999))
        tail.extend(60000)
        assertFalse(tail.expired(61000))
        assertFalse(tail.expired(119999))
        assertTrue(tail.expired(120000))
    }
    @Test fun defaultPreRollRetainsExactlyFiveSecondsOfPcm() {
        val ring = PreRoll()
        repeat(60) { ring.add(ByteArray(3200) { 1 }) }
        assertEquals(160000, ring.snapshot().size)
    }
}
