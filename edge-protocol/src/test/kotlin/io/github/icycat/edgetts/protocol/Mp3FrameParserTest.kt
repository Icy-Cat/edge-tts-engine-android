package io.github.icycat.edgetts.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Mp3FrameParserTest {
    /** 24kHz / 48kbps / MPEG-2 Layer III / mono：帧长 144 字节 */
    private fun frame(padding: Boolean = false): ByteArray {
        val b = ByteArray(if (padding) 145 else 144)
        b[0] = 0xFF.toByte(); b[1] = 0xF3.toByte() // MPEG2 L3 无 CRC
        b[2] = (0x60 or 0x04 or (if (padding) 0x02 else 0)).toByte() // brIdx=6(48k) srIdx=1(24k)
        b[3] = 0xC4.toByte() // mono
        return b
    }

    @Test fun header_24k48k() {
        val h = Mp3FrameParser.parseHeader(frame(), 0)!!
        assertEquals(144, h.frameLength); assertEquals(24000, h.sampleRate); assertEquals(1, h.channels); assertEquals(576, h.samplesPerFrame)
        assertEquals(145, Mp3FrameParser.parseHeader(frame(true), 0)!!.frameLength)
        assertNull(Mp3FrameParser.parseHeader(byteArrayOf(0, 0, 0, 0), 0))
    }

    @Test fun reassemblesFramesSplitAcrossArbitraryChunks() {
        val all = frame() + frame() + frame(true) + frame()
        for (step in listOf(1, 7, 100, 144, 1000)) {
            val p = Mp3FrameParser()
            val frames = all.toList().chunked(step).flatMap { p.feed(it.toByteArray()) }
            assertEquals("step=$step", 4, frames.size)
            assertEquals(all.size, frames.sumOf { it.bytes.size })
        }
    }

    @Test fun skipsId3AndResyncsOverGarbage() {
        val id3 = byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 4, 0, 0, 0, 0, 0, 20) + ByteArray(20)
        val p = Mp3FrameParser()
        val frames = p.feed(id3 + byteArrayOf(1, 2, 3) + frame() + frame())
        assertEquals(2, frames.size)
    }

    @Test fun parsesRealEdgeOutputIfPresent() {
        val res = javaClass.getResourceAsStream("/sample-nihao.mp3") ?: return
        val data = res.readBytes()
        val p = Mp3FrameParser()
        val frames = p.feed(data)
        assertTrue(frames.isNotEmpty())
        assertTrue(frames.all { it.sampleRate == 24000 && it.channels == 1 })
        assertTrue("trailing bytes ${p.pendingBytes}", p.pendingBytes < 144)
    }
}
