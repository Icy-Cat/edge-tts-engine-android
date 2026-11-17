package io.github.icycat.edgetts.protocol

// 本文件含对 rany2/edge-tts 7.3.1 communicate.py / voices.py 会话行为的修改移植。
// 来源与 GNU LGPLv3 only 见 third_party/edge-tts/NOTICE.md。

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Edge Read Aloud 的 IO 层（OkHttp WebSocket + HTTP）。阻塞式 API，需在后台线程调用。
 * 403 时按服务器 Date 头校正时钟并重试一次（与 edge-tts 一致）。
 * 调用方取消、连接空闲超过 [idleTimeoutMillis]、或握手失败都会结束本次请求并关掉 socket。
 */
class EdgeClient(
    val drm: Drm = Drm(),
    private val http: OkHttpClient = defaultHttpClient(),
    private val idleTimeoutMillis: Long = 60_000L,
    private val wssUrl: (unixSeconds: Double) -> String = { EdgeProtocol.wssUrl(it) },
    private val voiceListUrl: (unixSeconds: Double) -> String = { EdgeProtocol.voiceListUrl(it) },
) {
    /** 观察握手结果（101 / 403 ...），用于诊断与冒烟测试。 */
    @Volatile
    var handshakeObserver: ((code: Int) -> Unit)? = null

    private sealed interface Event {
        class Audio(val data: ByteArray) : Event
        object TurnEnd : Event
        object Closed : Event
        class Failure(val error: Throwable) : Event
    }

    /**
     * 合成 [text]；音频（MP3 字节流，按到达顺序）通过 [onAudio] 回调。
     * @param isCancelled 被轮询；返回 true 时抛 [EdgeException.Cancelled] 并取消 socket
     * @throws EdgeException.NoAudioReceived 服务端结束但没有任何音频
     */
    fun synthesize(
        text: String,
        voice: String = EdgeProtocol.DEFAULT_VOICE,
        rate: String = "+0%",
        pitch: String = "+0Hz",
        volume: String = "+0%",
        isCancelled: () -> Boolean = { false },
        onAudio: (ByteArray) -> Unit,
    ) {
        val chunks = EdgeProtocol.splitTextByByteLength(
            EdgeProtocol.escapeXml(EdgeProtocol.removeIncompatibleCharacters(text)),
            EdgeProtocol.MAX_TEXT_BYTES,
        )
        if (chunks.isEmpty()) throw EdgeException.NoAudioReceived()
        for (chunk in chunks) {
            if (isCancelled()) throw EdgeException.Cancelled()
            val ssml = EdgeProtocol.mkssml(voice, chunk, rate, pitch, volume)
            synthesizeChunk(ssml, isCancelled, onAudio)
        }
    }

    /** 单个 SSML 请求，带一次 403 时钟校正重试。 */
    fun synthesizeChunk(ssml: String, isCancelled: () -> Boolean, onAudio: (ByteArray) -> Unit) {
        if (isCancelled()) throw EdgeException.Cancelled()
        try {
            streamOnce(ssml, isCancelled, onAudio)
        } catch (e: EdgeException.HttpStatus) {
            if (e.code != 403) throw e
            if (isCancelled()) throw EdgeException.Cancelled()
            drm.handleServerDate(e.serverDate)
            if (isCancelled()) throw EdgeException.Cancelled()
            streamOnce(ssml, isCancelled, onAudio)
        }
    }

    private fun streamOnce(ssml: String, isCancelled: () -> Boolean, onAudio: (ByteArray) -> Unit) {
        if (isCancelled()) throw EdgeException.Cancelled()
        val queue = LinkedBlockingQueue<Event>()
        val rb = Request.Builder().url(wssUrl(drm.unixTimestamp()))
        EdgeProtocol.wssHeaders().forEach { (k, v) -> rb.header(k, v) }

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                handshakeObserver?.invoke(response.code)
                val timestamp = EdgeProtocol.dateToString()
                val configOk = webSocket.send(EdgeProtocol.speechConfigMessage(timestamp))
                val ssmlOk = webSocket.send(EdgeProtocol.ssmlMessage(EdgeProtocol.connectId(), timestamp, ssml))
                if (!configOk || !ssmlOk) {
                    queue.add(Event.Failure(EdgeException.WebSocketError("failed to send speech request")))
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    when (val path = EdgeProtocol.parseTextMessage(text).path) {
                        "turn.end" -> queue.add(Event.TurnEnd)
                        "audio.metadata", "response", "turn.start" -> Unit
                        else -> queue.add(Event.Failure(EdgeException.UnknownResponse("Unknown path received: $path")))
                    }
                } catch (e: Exception) {
                    queue.add(Event.Failure(e))
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                try {
                    val m = EdgeProtocol.parseBinaryMessage(bytes.toByteArray())
                    if (m.path != "audio") throw EdgeException.UnexpectedResponse("Received binary message, but the path is not audio.")
                    val ct = m.contentType
                    if (ct != "audio/mpeg" && ct != null) throw EdgeException.UnexpectedResponse("Received binary message, but with an unexpected Content-Type.")
                    if (ct == null) {
                        if (m.data.isEmpty()) return // 流结束标记
                        throw EdgeException.UnexpectedResponse("Received binary message with no Content-Type, but with data.")
                    }
                    if (m.data.isEmpty()) throw EdgeException.UnexpectedResponse("Received binary message, but it is missing the audio data.")
                    queue.add(Event.Audio(m.data))
                } catch (e: Exception) {
                    queue.add(Event.Failure(e))
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                try {
                    webSocket.close(1000, null)
                } catch (_: Exception) {
                }
                queue.add(Event.Closed)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                handshakeObserver?.let { obs -> if (response != null) obs(response.code) }
                val err: Throwable = if (response != null && response.code != 101) {
                    EdgeException.HttpStatus(
                        response.code,
                        response.header("Date"),
                        "WebSocket handshake failed: ${response.code}",
                    )
                } else EdgeException.WebSocketError(t.message ?: t.javaClass.simpleName, t)
                queue.add(Event.Failure(err))
            }
        }

        val ws = http.newWebSocket(rb.build(), listener)
        var audioReceived = false
        val idleNanos = idleTimeoutMillis * 1_000_000L
        var lastData = System.nanoTime()
        try {
            while (true) {
                if (isCancelled()) throw EdgeException.Cancelled()
                val ev = queue.poll(50, TimeUnit.MILLISECONDS)
                if (ev == null) {
                    if (System.nanoTime() - lastData > idleNanos) {
                        throw EdgeException.WebSocketError("idle timeout", SocketTimeoutException("idle timeout"))
                    }
                    continue
                }
                lastData = System.nanoTime()
                when (ev) {
                    is Event.Audio -> {
                        audioReceived = true
                        onAudio(ev.data)
                    }
                    Event.TurnEnd -> {
                        if (!audioReceived) throw EdgeException.NoAudioReceived()
                        return
                    }
                    // 上游在连接正常结束时，只要已经收到音频就视为成功，不要求一定先看到 turn.end。
                    Event.Closed -> {
                        if (!audioReceived) throw EdgeException.NoAudioReceived()
                        return
                    }
                    is Event.Failure -> throw ev.error
                }
            }
        } finally {
            ws.cancel()
        }
    }

    /** 拉取语音列表；403 时校正时钟重试一次。 */
    fun listVoices(): List<EdgeVoice> {
        return try {
            fetchVoices()
        } catch (e: EdgeException.HttpStatus) {
            if (e.code != 403) throw e
            drm.handleServerDate(e.serverDate)
            fetchVoices()
        }
    }

    private fun fetchVoices(): List<EdgeVoice> {
        val rb = Request.Builder().url(voiceListUrl(drm.unixTimestamp()))
        EdgeProtocol.voiceHeaders().forEach { (k, v) -> rb.header(k, v) }
        try {
            http.newCall(rb.build()).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw EdgeException.HttpStatus(resp.code, resp.header("Date"), "voices/list failed: ${resp.code}")
                }
                val body = resp.body ?: throw EdgeException.UnexpectedResponse("voices/list returned an empty body")
                return EdgeVoice.parseList(body.string())
            }
        } catch (e: EdgeException) {
            throw e
        } catch (e: IOException) {
            throw EdgeException.WebSocketError(e.javaClass.simpleName, e)
        }
    }

    /** 关掉这个客户端自己的连接池和线程。进程级单例不需要调用。 */
    fun close() {
        http.dispatcher.cancelAll()
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
    }

    companion object {
        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}
