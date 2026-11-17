package io.github.icycat.edgetts.protocol

import java.io.File
import kotlin.system.exitProcess

/**
 * 真实网络冒烟测试（不依赖 Android）：
 *   ./gradlew :edge-protocol:smoke
 * 1) 拉语音列表；2) 用 zh-CN-XiaoxiaoNeural 合成「你好」，打印握手状态码和收到的 MP3 字节数；
 * 3) 故意把本地时钟拨快 1 小时，验证 403 -> Date 头校正 -> 重试 的路径。
 */
fun main(args: Array<String>) {
    val out = File(args.firstOrNull() ?: "smoke.mp3")
    out.absoluteFile.parentFile?.mkdirs()
    var failed = false

    fun run(label: String, client: EdgeClient, text: String = "你好", voice: String = "zh-CN-XiaoxiaoNeural"): Boolean {
        val codes = mutableListOf<Int>()
        client.handshakeObserver = { codes += it }
        val bytes = java.io.ByteArrayOutputStream()
        return try {
            client.synthesize(text, voice) { bytes.write(it) }
            println("[$label] handshake codes=$codes audioBytes=${bytes.size()} skew=${"%.1f".format(client.drm.clockSkewSeconds)}s")
            if (label == "normal") out.writeBytes(bytes.toByteArray())
            val frames = Mp3FrameParser().feed(bytes.toByteArray())
            val sr = frames.firstOrNull()?.sampleRate
            val dur = frames.sumOf { it.samplesPerFrame } * 1000L / (sr ?: 1)
            println("[$label] mp3 frames=${frames.size} sampleRate=$sr channels=${frames.firstOrNull()?.channels} duration≈${dur}ms")
            bytes.size() > 0
        } catch (e: Exception) {
            println("[$label] FAILED handshake codes=$codes: $e")
            false
        }
    }

    try {
        val voices = EdgeClient().listVoices()
        println("[voices] count=${voices.size} zh-CN=${voices.count { it.locale == "zh-CN" }} en-US=${voices.count { it.locale == "en-US" }} locales=${voices.map { it.locale }.toSet().size}")
    } catch (e: Exception) {
        println("[voices] FAILED: $e"); failed = true
    }

    if (!run("normal", EdgeClient())) failed = true

    val skewed = Drm { System.currentTimeMillis() + 3_600_000L } // 本地时钟快 1 小时
    if (!run("skewed+1h", EdgeClient(skewed))) failed = true

    // 超过单次 4096 字节上限的长文本（多块）+ 英文语音，验证切分与多轮请求
    val longZh = "今天天气很好，我们一起去公园散步。".repeat(130) // ≈ 4.4KB UTF-8 -> 2 个请求
    if (!run("long-multi-chunk", EdgeClient(), longZh)) failed = true
    if (!run("en-US", EdgeClient(), "Hello, this is a test.", "en-US-AriaNeural")) failed = true

    println(if (failed) "SMOKE: FAIL" else "SMOKE: OK  (mp3 -> ${out.absolutePath})")
    exitProcess(if (failed) 1 else 0)
}
