package io.github.icycat.edgetts.protocol

import java.io.ByteArrayOutputStream

/**
 * 流式 MP3 切帧器：Edge 的 WebSocket 音频包不按 MP3 帧对齐，
 * 而 MediaCodec 的 MP3 解码器每个输入 buffer 需要整帧，所以先在这里重组。
 * 处理 ID3v2 头、Xing/Info 头（当作普通帧，解码为静音）、坏数据重新同步。
 */
class Mp3FrameParser {
    data class Frame(val bytes: ByteArray, val sampleRate: Int, val channels: Int, val samplesPerFrame: Int)

    private var buf = ByteArray(0)
    private var skipId3 = 0

    /** 喂入新数据，返回其中已完整的帧。 */
    fun feed(data: ByteArray): List<Frame> {
        buf += data
        val frames = ArrayList<Frame>()
        var pos = 0
        while (true) {
            // ID3v2: "ID3" ver(2) flags(1) size(4, syncsafe)
            if (skipId3 == 0 && buf.size - pos >= 10 &&
                buf[pos] == 'I'.code.toByte() && buf[pos + 1] == 'D'.code.toByte() && buf[pos + 2] == '3'.code.toByte()
            ) {
                val size = (buf[pos + 6].toInt() and 0x7F shl 21) or (buf[pos + 7].toInt() and 0x7F shl 14) or
                    (buf[pos + 8].toInt() and 0x7F shl 7) or (buf[pos + 9].toInt() and 0x7F)
                val footer = if (buf[pos + 5].toInt() and 0x10 != 0) 10 else 0
                skipId3 = 10 + size + footer
            }
            if (skipId3 > 0) {
                val n = minOf(skipId3, buf.size - pos)
                pos += n
                skipId3 -= n
                if (skipId3 > 0) break
                continue
            }
            if (buf.size - pos < 4) break
            val h = parseHeader(buf, pos)
            if (h == null) {
                pos++ // 重新同步
                continue
            }
            if (buf.size - pos < h.frameLength) break
            frames += Frame(buf.copyOfRange(pos, pos + h.frameLength), h.sampleRate, h.channels, h.samplesPerFrame)
            pos += h.frameLength
        }
        buf = buf.copyOfRange(pos, buf.size)
        return frames
    }

    /** 未消费的尾巴字节数（流结束时通常 0；可能是 ID3v1 "TAG" 或半帧）。 */
    val pendingBytes: Int get() = buf.size

    class Header(val frameLength: Int, val sampleRate: Int, val channels: Int, val samplesPerFrame: Int)

    companion object {
        private val BITRATES_V1_L3 = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0)
        private val BITRATES_V2_L3 = intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, 0)
        private val RATES_V1 = intArrayOf(44100, 48000, 32000, 0)

        /** 只支持 Layer III（Edge 输出格式）。非法返回 null。 */
        fun parseHeader(b: ByteArray, off: Int): Header? {
            if (b.size - off < 4) return null
            val b0 = b[off].toInt() and 0xFF
            val b1 = b[off + 1].toInt() and 0xFF
            val b2 = b[off + 2].toInt() and 0xFF
            val b3 = b[off + 3].toInt() and 0xFF
            if (b0 != 0xFF || (b1 and 0xE0) != 0xE0) return null
            val versionBits = (b1 shr 3) and 3          // 3=MPEG1, 2=MPEG2, 0=MPEG2.5
            val layerBits = (b1 shr 1) and 3            // 1=Layer III
            if (versionBits == 1 || layerBits != 1) return null
            val brIdx = (b2 shr 4) and 0xF
            val srIdx = (b2 shr 2) and 3
            val padding = (b2 shr 1) and 1
            if (brIdx == 0 || brIdx == 15 || srIdx == 3) return null
            val mpeg1 = versionBits == 3
            val bitrate = (if (mpeg1) BITRATES_V1_L3[brIdx] else BITRATES_V2_L3[brIdx]) * 1000
            val base = RATES_V1[srIdx]
            val sampleRate = when (versionBits) { 3 -> base; 2 -> base / 2; else -> base / 4 }
            val samples = if (mpeg1) 1152 else 576
            val len = (if (mpeg1) 144 else 72) * bitrate / sampleRate + padding
            val channels = if (((b3 shr 6) and 3) == 3) 1 else 2
            if (len < 4) return null
            return Header(len, sampleRate, channels, samples)
        }
    }
}

/** 把多段 ByteArray 拼起来的小工具（测试/冒烟用）。 */
fun ByteArrayOutputStream.appendAll(parts: Iterable<ByteArray>) = parts.forEach { write(it) }
