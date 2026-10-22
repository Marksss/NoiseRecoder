package com.example.noiserecorder

import java.io.RandomAccessFile
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WavWriterTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun finalizedWavHasCorrectHeaderAndUnchangedPcm() {
        val file = temp.newFile("sample.wav")
        val pcm = ByteArray(32000) { (it % 255).toByte() }
        WavWriter(file).use { it.write(pcm) }
        val bytes = file.readBytes()
        assertEquals(32044, bytes.size)
        assertEquals("RIFF", String(bytes.copyOfRange(0, 4)))
        assertEquals("WAVE", String(bytes.copyOfRange(8, 12)))
        val header = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        assertEquals(32036, header.getInt(4)); assertEquals(16000, header.getInt(24)); assertEquals(32000, header.getInt(40))
        assertArrayEquals(pcm, bytes.copyOfRange(44, bytes.size))
    }
    @Test fun interruptedHeaderCanBeRecoveredWithoutChangingAudio() {
        val file = temp.newFile("interrupted.wav")
        RandomAccessFile(file, "rw").use { it.write(ByteArray(44)); it.write(byteArrayOf(1, 2, 3, 4)) }
        WavWriter.repair(file)
        val bytes = file.readBytes()
        assertEquals("RIFF", String(bytes.copyOfRange(0, 4)))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), bytes.copyOfRange(44, 48))
    }
}
