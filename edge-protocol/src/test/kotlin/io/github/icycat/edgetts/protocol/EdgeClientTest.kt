package io.github.icycat.edgetts.protocol

import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class EdgeClientTest {
    private val server = MockWebServer()
    private val http = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(2, TimeUnit.SECONDS)
        .build()
    private var client: EdgeClient? = null

    @After fun tearDown() {
        try { client?.close() } catch (_: Exception) { }
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
        try { server.shutdown() } catch (_: Exception) { }
    }

    private fun newClient(idleMs: Long = 1_000, clock: () -> Long = { 1_735_689_899_000L }): EdgeClient {
        server.start()
        val ws = server.url("/tts").toString().replace("http://", "ws://")
        val voices = server.url("/voices").toString()
        return EdgeClient(
            drm = Drm(clock),
            http = http,
            idleTimeoutMillis = idleMs,
            wssUrl = { unix ->
                "$ws?Sec-MS-GEC=${EdgeProtocol.secMsGec(unix)}" +
                    "&Sec-MS-GEC-Version=${EdgeProtocol.SEC_MS_GEC_VERSION}" +
                    "&ConnectionId=${EdgeProtocol.connectId()}"
            },
            voiceListUrl = { voices },
        ).also { client = it }
    }

    @Test(timeout = 8_000) fun audioThenTurnEndReturnsBytes() {
        val c = newClient()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(ByteString.of(*audioFrame(byteArrayOf(9, 8, 7))))
                webSocket.send("Path:turn.end\r\n\r\n")
                webSocket.close(1000, "ok")
            }
        }))
        val got = ArrayList<Byte>()
        c.synthesize("hi") { got += it.toList() }
        assertEquals(listOf<Byte>(9, 8, 7), got)
        val path = server.takeRequest().path.orEmpty()
        assertTrue(path.contains("Sec-MS-GEC-Version=2-"))
        assertTrue(path.contains("Sec-MS-GEC="))
    }

    @Test(timeout = 8_000) fun gracefulCloseAfterAudioSucceedsWithoutTurnEnd() {
        val c = newClient()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(ByteString.of(*audioFrame(byteArrayOf(1, 2))))
                webSocket.close(1000, "bye")
            }
        }))
        val got = ArrayList<Byte>()
        c.synthesize("hi") { got += it.toList() }
        assertEquals(listOf<Byte>(1, 2), got)
    }

    @Test(timeout = 8_000) fun closeWithoutAudioIsNoAudioReceived() {
        val c = newClient()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.close(1000, "empty")
            }
        }))
        assertThrows(EdgeException.NoAudioReceived::class.java) { c.synthesize("hi") { } }
    }

    @Test(timeout = 8_000) fun turnEndWithoutAudioIsNoAudioReceived() {
        val c = newClient()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send("Path:turn.end\r\n\r\n")
            }
        }))
        assertThrows(EdgeException.NoAudioReceived::class.java) { c.synthesize("hi") { } }
    }

    @Test(timeout = 8_000) fun forbiddenWithDateRetriesOnce() {
        val c = newClient()
        server.enqueue(MockResponse().setResponseCode(403).addHeader("Date", "Wed, 01 Jan 2025 00:05:01 GMT"))
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(ByteString.of(*audioFrame(byteArrayOf(4))))
                webSocket.send("Path:turn.end\r\n\r\n")
            }
        }))
        val got = ArrayList<Byte>()
        c.synthesize("hi") { got += it.toList() }
        assertEquals(listOf<Byte>(4), got)
        assertEquals(2.0, c.drm.clockSkewSeconds, 1e-6)
        assertEquals(2, server.requestCount)
    }

    @Test(timeout = 8_000) fun forbiddenWithoutDateDoesNotRetry() {
        val c = newClient()
        server.enqueue(MockResponse().setResponseCode(403))
        val error = assertThrows(EdgeException.SkewAdjustment::class.java) { c.synthesize("hi") { } }
        assertTrue(SynthesisErrors.userMessage(error).contains("时钟"))
        assertEquals(1, server.requestCount)
    }

    @Test(timeout = 8_000) fun http500IsNotRetriedByTheClient() {
        val c = newClient()
        server.enqueue(MockResponse().setResponseCode(500))
        val error = assertThrows(EdgeException.HttpStatus::class.java) { c.synthesize("hi") { } }
        assertEquals(500, error.code)
        assertEquals(1, server.requestCount)
        assertTrue(SynthesisErrors.isRetryable(error))
    }

    @Test(timeout = 8_000) fun cancelUnblocksWithoutWaitingForIdleTimeout() {
        val c = newClient(idleMs = 30_000)
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {}))
        val cancel = AtomicBoolean(false)
        val started = System.nanoTime()
        val worker = Thread {
            try {
                c.synthesize("hi", isCancelled = { cancel.get() }) { }
            } catch (_: EdgeException.Cancelled) {
            }
        }
        worker.isDaemon = true
        worker.start()
        Thread.sleep(150)
        cancel.set(true)
        worker.join(3_000)
        assertTrue("cancel did not finish", !worker.isAlive)
        assertTrue(System.nanoTime() - started < 5_000_000_000L)
    }

    @Test(timeout = 8_000) fun idleTimeoutFailsASilentSocket() {
        val c = newClient(idleMs = 300)
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {}))
        val error = assertThrows(EdgeException.WebSocketError::class.java) { c.synthesize("hi") { } }
        assertTrue(error.cause is java.net.SocketTimeoutException)
        assertEquals(SynthesisFailureKind.TIMEOUT, SynthesisErrors.failureKind(error))
        assertTrue(SynthesisErrors.isRetryable(error))
    }

    @Test(timeout = 8_000) fun voiceListRetries403ThenParsesBody() {
        val c = newClient()
        server.enqueue(MockResponse().setResponseCode(403).addHeader("Date", "Wed, 01 Jan 2025 00:05:01 GMT"))
        server.enqueue(MockResponse().setBody("""[{"ShortName":"zh-CN-XiaoxiaoNeural","Locale":"zh-CN","Gender":"Female"}]"""))
        val voices = c.listVoices()
        assertEquals("zh-CN-XiaoxiaoNeural", voices.single().shortName)
        assertEquals(2, server.requestCount)
    }

    @Test fun userMessageStripsRequestUrls() {
        val leaked = EdgeException.WebSocketError("failed wss://example.invalid/edge?Sec-MS-GEC=ABCDEF&ConnectionId=1")
        val text = SynthesisErrors.userMessage(leaked)
        assertTrue(!text.contains("://"))
        assertTrue(!text.contains("ABCDEF"))
        assertTrue(!text.contains("Sec-MS-GEC=ABCDEF"))
    }

    private fun audioFrame(payload: ByteArray): ByteArray {
        val header = "Content-Type:audio/mpeg\r\nPath:audio\r\n".toByteArray()
        return byteArrayOf((header.size shr 8).toByte(), (header.size and 0xFF).toByte()) + header + payload
    }
}
