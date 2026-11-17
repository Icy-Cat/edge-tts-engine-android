package io.github.icycat.edgetts

import android.media.MediaCodec
import android.media.MediaFormat
import io.github.icycat.edgetts.protocol.EdgeProtocol
import io.github.icycat.edgetts.protocol.Mp3FrameParser
import java.io.IOException

/**
 * 流式 MP3 → PCM16 单声道解码（MediaCodec）。
 * 网络包先经 [Mp3FrameParser] 重组为完整帧，再逐帧送入解码器；输出通过 [onPcm] 回调。
 * 每个 SSML 请求（一段独立的 MP3 流）使用一个新实例。
 */
class Mp3PcmDecoder(private val onPcm: (ByteArray) -> Unit) : AutoCloseable {
    private val parser = Mp3FrameParser()
    private val codec: MediaCodec = createStartedDecoder()
    private val info = MediaCodec.BufferInfo()
    private var ptsUs = 0L
    private var outChannels = EdgeProtocol.OUTPUT_CHANNELS
    private var outRate = EdgeProtocol.OUTPUT_SAMPLE_RATE
    private var pcmBytes = 0
    private var released = false

    private fun createStartedDecoder(): MediaCodec {
        val created = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_MPEG)
        try {
            val fmt = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_MPEG, EdgeProtocol.OUTPUT_SAMPLE_RATE, EdgeProtocol.OUTPUT_CHANNELS
            )
            fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
            created.configure(fmt, null, null, 0)
            created.start()
            return created
        } catch (t: Throwable) {
            try { created.release() } catch (_: Exception) { }
            throw t
        }
    }

    /** 喂入网络收到的一段 MP3 字节。 */
    fun feed(mp3: ByteArray) {
        for (f in parser.feed(mp3)) {
            if (f.sampleRate != EdgeProtocol.OUTPUT_SAMPLE_RATE) {
                throw IOException("Unexpected MP3 sample rate ${f.sampleRate}, expected ${EdgeProtocol.OUTPUT_SAMPLE_RATE}")
            }
            queueInput(f.bytes, 0)
            ptsUs += f.samplesPerFrame * 1_000_000L / f.sampleRate
            drain(false)
        }
    }

    /**
     * 流结束：送 EOS 并取完剩余输出。
     * 部分机型的 MP3 解码器不回报 EOS。已经交出 PCM 时把尾部超时当成正常结束，
     * 避免一段已经播放的音频再被记成失败。
     */
    fun finish() {
        queueInput(ByteArray(0), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        try {
            drain(true)
        } catch (e: IOException) {
            if (pcmBytes == 0) throw e
        }
    }

    private fun queueInput(data: ByteArray, flags: Int) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (true) {
            val idx = codec.dequeueInputBuffer(10_000)
            if (idx >= 0) {
                val buf = codec.getInputBuffer(idx) ?: throw IOException("null input buffer")
                buf.clear()
                buf.put(data)
                codec.queueInputBuffer(idx, 0, data.size, ptsUs, flags)
                return
            }
            drain(false) // 输入满了，先把输出取走
            if (System.nanoTime() > deadline) throw IOException("MediaCodec input stalled")
        }
    }

    private fun drain(untilEos: Boolean) {
        val waitNs = if (untilEos && pcmBytes > 0) 500_000_000L else 5_000_000_000L
        val deadline = System.nanoTime() + waitNs
        while (true) {
            val idx = codec.dequeueOutputBuffer(info, if (untilEos) 10_000 else 0)
            when {
                idx >= 0 -> {
                    val buf = codec.getOutputBuffer(idx)
                    if (buf != null && info.size > 0) {
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        val raw = ByteArray(info.size)
                        buf.get(raw)
                        val pcm = toMono16(raw)
                        pcmBytes += pcm.size
                        onPcm(pcm)
                    }
                    val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    codec.releaseOutputBuffer(idx, false)
                    if (eos) return
                }
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val f = codec.outputFormat
                    if (f.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) outChannels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    if (f.containsKey(MediaFormat.KEY_SAMPLE_RATE)) outRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    if (outRate != EdgeProtocol.OUTPUT_SAMPLE_RATE) {
                        throw IOException("Decoder output rate $outRate != ${EdgeProtocol.OUTPUT_SAMPLE_RATE}")
                    }
                }
                idx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!untilEos) return
                    if (System.nanoTime() > deadline) throw IOException("MediaCodec drain timed out")
                }
                else -> Unit // INFO_OUTPUT_BUFFERS_CHANGED 等
            }
        }
    }

    /** callback.start 声明单声道；万一解码器输出立体声，则平均成单声道。 */
    private fun toMono16(raw: ByteArray): ByteArray {
        if (outChannels <= 1) return raw
        val frames = raw.size / (2 * outChannels)
        val out = ByteArray(frames * 2)
        for (i in 0 until frames) {
            var sum = 0
            for (c in 0 until outChannels) {
                val p = (i * outChannels + c) * 2
                sum += ((raw[p + 1].toInt() shl 8) or (raw[p].toInt() and 0xFF)).toShort().toInt()
            }
            val v = sum / outChannels
            out[i * 2] = v.toByte()
            out[i * 2 + 1] = (v shr 8).toByte()
        }
        return out
    }

    override fun close() {
        if (released) return
        released = true
        try { codec.stop() } catch (_: Exception) { }
        try { codec.release() } catch (_: Exception) { }
    }
}
