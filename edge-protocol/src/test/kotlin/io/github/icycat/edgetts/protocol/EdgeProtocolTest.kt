package io.github.icycat.edgetts.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class EdgeProtocolTest {

    // 向量由 rany2/edge-tts master 的 generate_sec_ms_gec_v2() 在固定时间戳下生成（2026-11-17）。
    // v1 的纯 SHA-256 摘要已经不能通过服务端校验。
    private val vectors = listOf(
        0.0 to "52BC7A17B50DFEC9-28C4394775C8751CD2F6859C030C06DB1489ACAB3B3A7651831FC26895177ED6",
        1735689600.0 to "54404AFE67C9B44A-E72E51D8B4F5F75E9A96B7E20D7877227455BC310433BD30C817CD54A1D9313E",
        1735689899.7 to "54404AFE67C9B44A-E72E51D8B4F5F75E9A96B7E20D7877227455BC310433BD30C817CD54A1D9313E", // 同一 5 分钟窗口
        1735689900.0 to "573B5AB47EF76DEF-382062865F8E6ED3D6FF14EFCF08C89427D829196C82586577B5BD64F1FFC0EF", // 下一窗口
        1759418055.25 to "D7329CD8B6401CF2-6CEC386B88CBB4781EB2508FDD214B5D963EF5ECD3AF96DAA0AA7BB596F7FDA6",
        1759418399.999 to "6879F5239BD6B071-B641D93F8D742C033CD102440668BFF9781688588EA55438A8B8D3806332E86F",
    )

    @Test fun secMsGec_matchesReferenceVectors() {
        for ((t, expected) in vectors) assertEquals("t=$t", expected, EdgeProtocol.secMsGec(t))
    }

    @Test fun secMsGec_hasV2Shape() {
        val g = EdgeProtocol.secMsGec(1.7e9)
        assertTrue(g.matches(Regex("[0-9A-F]{16}-[0-9A-F]{64}")))
        assertTrue(EdgeProtocol.SEC_MS_GEC_VERSION.startsWith("2-"))
    }

    @Test fun secMsGec_changesEvery5Minutes() {
        val base = 1735689600.0 // 300 的倍数（1735689600 % 300 == 0）
        assertEquals(EdgeProtocol.secMsGec(base), EdgeProtocol.secMsGec(base + 299.9))
        assertFalse(EdgeProtocol.secMsGec(base) == EdgeProtocol.secMsGec(base + 300))
        assertFalse(EdgeProtocol.secMsGec(base) == EdgeProtocol.secMsGec(base - 0.001))
    }

    @Test fun drm_appliesClockSkewFromServerDate() {
        val now = 1_735_689_899_000L // 窗口末尾
        val drm = Drm { now }
        // 服务器时间比本地快 2 秒 -> 跨入下一窗口
        drm.handleServerDate("Wed, 01 Jan 2025 00:05:01 GMT") // = 1735689901
        assertEquals(2.0, drm.clockSkewSeconds, 1e-6)
        assertEquals(EdgeProtocol.secMsGec(1735689901.0), drm.secMsGec())
    }

    @Test fun drm_concurrentSkewDoesNotDoubleApply() {
        val now = 1_735_689_899_000L
        val drm = Drm { now }
        val threads = List(8) { Thread { drm.handleServerDate("Wed, 01 Jan 2025 00:05:01 GMT") } }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertEquals(2.0, drm.clockSkewSeconds, 1e-6)
    }

    @Test fun drm_missingOrBadDateThrows() {
        assertThrows(EdgeException.SkewAdjustment::class.java) { Drm().handleServerDate(null) }
        assertThrows(EdgeException.SkewAdjustment::class.java) { Drm().handleServerDate("not a date") }
    }

    @Test fun parseRfc2616Date() {
        assertEquals(1735689901.0, EdgeProtocol.parseRfc2616Date("Wed, 01 Jan 2025 00:05:01 GMT")!!, 0.0)
        assertNull(EdgeProtocol.parseRfc2616Date("garbage"))
    }

    @Test fun urls_carryGecAndVersion() {
        val u = EdgeProtocol.wssUrl(1735689600.0, "abc123")
        assertTrue(u.startsWith("wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1?TrustedClientToken=6A5AA1D4EAFF4E9FB37E23D68491D6F4"))
        assertTrue(u.contains("&ConnectionId=abc123"))
        assertTrue(u.contains("&Sec-MS-GEC=54404AFE67C9B44A-E72E51D8B4F5F75E9A96B7E20D7877227455BC310433BD30C817CD54A1D9313E"))
        assertTrue(u.endsWith("&Sec-MS-GEC-Version=2-143.0.3650.75"))
        assertTrue(EdgeProtocol.voiceListUrl(0.0).contains("voices/list?trustedclienttoken="))
    }

    @Test fun headers_matchReferenceShape() {
        val h = EdgeProtocol.wssHeaders("ABCD")
        assertEquals("chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold", h["Origin"])
        assertEquals("muid=ABCD;", h["Cookie"])
        assertTrue(h["User-Agent"]!!.contains("Chrome/143.0.0.0") && h["User-Agent"]!!.contains("Edg/143.0.0.0"))
        assertTrue(EdgeProtocol.voiceHeaders()["Sec-CH-UA"]!!.contains("\"Microsoft Edge\";v=\"143\""))
        assertEquals(32, EdgeProtocol.generateMuid().length)
    }

    // ---- SSML ----

    @Test fun ssml_matchesReferenceTemplate() {
        val s = EdgeProtocol.mkssml("zh-CN-XiaoxiaoNeural", "你好", "+10%", "-5Hz", "+0%")
        assertEquals(
            "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='en-US'>" +
                "<voice name='Microsoft Server Speech Text to Speech Voice (zh-CN, XiaoxiaoNeural)'>" +
                "<prosody pitch='-5Hz' rate='+10%' volume='+0%'>你好</prosody></voice></speak>",
            s,
        )
    }

    @Test fun voiceName_conversion() {
        assertEquals("Microsoft Server Speech Text to Speech Voice (en-US, AriaNeural)", EdgeProtocol.fullVoiceName("en-US-AriaNeural"))
        assertEquals("Microsoft Server Speech Text to Speech Voice (zh-CN-liaoning, XiaobeiNeural)", EdgeProtocol.fullVoiceName("zh-CN-liaoning-XiaobeiNeural"))
        assertEquals("Microsoft Server Speech Text to Speech Voice (fil-PH, AngeloNeural)", EdgeProtocol.fullVoiceName("fil-PH-AngeloNeural"))
        val full = "Microsoft Server Speech Text to Speech Voice (cy-GB, NiaNeural)"
        assertEquals(full, EdgeProtocol.fullVoiceName(full))
    }

    @Test fun ssml_rejectsBadParams() {
        assertThrows(IllegalArgumentException::class.java) { EdgeProtocol.mkssml("en-US-AriaNeural", "x", rate = "10%") }
        assertThrows(IllegalArgumentException::class.java) { EdgeProtocol.mkssml("en-US-AriaNeural", "x", pitch = "+5%") }
        assertThrows(IllegalArgumentException::class.java) { EdgeProtocol.mkssml("en-US-Aria'Neural", "x") }
    }

    @Test fun escape_andControlChars() {
        assertEquals("a &amp; b &lt;c&gt;", EdgeProtocol.escapeXml("a & b <c>"))
        assertEquals("a b c", EdgeProtocol.removeIncompatibleCharacters("a\u000Bb\u0001c"))
        assertEquals("a\nb\tc\r", EdgeProtocol.removeIncompatibleCharacters("a\nb\tc\r")) // 9,10,13 保留
    }

    @Test fun messages_haveEdgeFraming() {
        val cfg = EdgeProtocol.speechConfigMessage("TS")
        assertTrue(cfg.startsWith("X-Timestamp:TS\r\nContent-Type:application/json; charset=utf-8\r\nPath:speech.config\r\n\r\n{\"context\""))
        assertTrue(cfg.contains("\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\""))
        assertTrue(cfg.contains("\"sentenceBoundaryEnabled\":\"true\",\"wordBoundaryEnabled\":\"false\""))
        val m = EdgeProtocol.ssmlMessage("REQ", "TS", "<speak/>")
        assertEquals("X-RequestId:REQ\r\nContent-Type:application/ssml+xml\r\nX-Timestamp:TSZ\r\nPath:ssml\r\n\r\n<speak/>", m)
        assertTrue(EdgeProtocol.dateToString().endsWith("GMT+0000 (Coordinated Universal Time)"))
        assertEquals("Thu Jan 01 1970 00:00:00 GMT+0000 (Coordinated Universal Time)", EdgeProtocol.dateToString(java.util.Date(0)))
    }

    // ---- 消息解析 ----

    private fun binary(headers: String, payload: ByteArray): ByteArray {
        val h = headers.toByteArray()
        return byteArrayOf((h.size shr 8).toByte(), (h.size and 0xFF).toByte()) + h + payload
    }

    @Test fun parseBinary_audio() {
        val data = binary("X-RequestId:abc\r\nContent-Type:audio/mpeg\r\nX-Timestamp:t\r\nPath:audio\r\n", byteArrayOf(1, 2, 3))
        val m = EdgeProtocol.parseBinaryMessage(data)
        assertEquals("audio", m.path)
        assertEquals("audio/mpeg", m.contentType)
        assertEquals(listOf<Byte>(1, 2, 3), m.data.toList())
    }

    @Test fun parseBinary_streamEndMarkerHasNoContentType() {
        val m = EdgeProtocol.parseBinaryMessage(binary("X-RequestId:abc\r\nPath:audio\r\n", ByteArray(0)))
        assertNull(m.contentType); assertEquals(0, m.data.size)
    }

    @Test fun parseBinary_malformed() {
        assertThrows(EdgeException.UnexpectedResponse::class.java) { EdgeProtocol.parseBinaryMessage(byteArrayOf(0)) }
        assertThrows(EdgeException.UnexpectedResponse::class.java) { EdgeProtocol.parseBinaryMessage(byteArrayOf(0, 99, 1)) }
    }

    @Test fun parseText_turnEnd() {
        val m = EdgeProtocol.parseTextMessage("X-RequestId:abc\r\nPath:turn.end\r\nX-Timestamp:t\r\n\r\n{}")
        assertEquals("turn.end", m.path); assertEquals("{}", m.body)
        assertEquals("audio.metadata", EdgeProtocol.parseTextMessage("Path:audio.metadata\r\n\r\n{\"Metadata\":[]}").path)
    }

    // ---- 切分 ----

    @Test fun split_shortTextIsOneChunk() {
        assertEquals(listOf("你好"), EdgeProtocol.splitTextByByteLength("  你好 \n", 4096))
        assertTrue(EdgeProtocol.splitTextByByteLength("   \n ", 4096).isEmpty())
    }

    @Test fun split_chineseWithoutSpacesRespectsByteLimit() {
        val text = "我们今天去公园玩，" .repeat(200) // 无空格、无句末标点之外的断点
        val chunks = EdgeProtocol.splitTextByByteLength(text, 300)
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.toByteArray().size <= 300 })
        assertEquals(text.replace("，", "，"), chunks.joinToString(""))
    }

    @Test fun split_prefersSentenceBoundary() {
        val s = "第一句话。第二句话。第三句话。"
        val chunks = EdgeProtocol.splitTextByByteLength(s, 30) // 每句 ~ 15 字节
        assertTrue(chunks.all { it.endsWith("。") })
        assertEquals(s, chunks.joinToString(""))
    }

    @Test fun split_prefersNewlineThenSpace() {
        assertEquals(listOf("aaaa bbbb", "cccc"), EdgeProtocol.splitTextByByteLength("aaaa bbbb\ncccc", 12))
        assertEquals(listOf("aaaa", "bbbb", "cccc"), EdgeProtocol.splitTextByByteLength("aaaa bbbb cccc", 9))
    }

    @Test fun split_doesNotBreakXmlEntitiesOrSurrogates() {
        val escaped = EdgeProtocol.escapeXml("a&".repeat(50) + "b")
        val chunks = EdgeProtocol.splitTextByByteLength(escaped, 17)
        for (c in chunks) {
            val amp = c.lastIndexOf('&')
            if (amp >= 0) assertTrue("dangling entity in '$c'", c.indexOf(';', amp) > amp)
        }
        assertEquals(escaped, chunks.joinToString(""))
        val emoji = "😀".repeat(40)
        val ec = EdgeProtocol.splitTextByByteLength(emoji, 10)
        assertTrue(ec.all { it.toByteArray().size <= 10 })
        assertEquals(emoji, ec.joinToString(""))
    }

    // ---- Prosody ----

    @Test fun prosody_mapping() {
        assertEquals("+0%", Prosody.rate(100))
        assertEquals("+50%", Prosody.rate(150))
        assertEquals("-30%", Prosody.rate(100, -30))
        assertEquals("+200%", Prosody.rate(1000)) // 上限
        assertEquals("-90%", Prosody.rate(0, -100)) // 下限
        assertEquals("+0Hz", Prosody.pitch(100))
        assertEquals("+25Hz", Prosody.pitch(150))
        assertEquals("-10Hz", Prosody.pitch(100, -10))
        assertEquals("+50Hz", Prosody.pitch(400)) // 上限
        // 结果必须能通过 SSML 校验
        EdgeProtocol.mkssml("en-US-AriaNeural", "x", Prosody.rate(37, 5), Prosody.pitch(25))
    }

    // ---- 语音列表解析 ----

    @Test fun voices_parse() {
        val json = """[{"Name":"Microsoft Server Speech Text to Speech Voice (zh-CN-liaoning, XiaobeiNeural)",
            "ShortName":"zh-CN-liaoning-XiaobeiNeural","Gender":"Female","Locale":"zh-CN","LocaleName":"Chinese (Mainland)",
            "FriendlyName":"Microsoft Xiaobei Online (Natural) - Chinese (Northeastern Mandarin)","Status":"GA",
            "VoiceTag":{"ContentCategories":["General"],"VoicePersonalities":["Friendly"]}},
            {"ShortName":"en-US-AriaNeural","Locale":"en-US","Gender":"Female"}]"""
        val v = EdgeVoice.parseList(json)
        assertEquals(2, v.size)
        assertEquals("zh-CN", v[0].locale)
        assertEquals("liaoning-Xiaobei", v[0].displayName)
        assertEquals("zh", v[0].language); assertEquals("CN", v[0].region)
        assertEquals(listOf("General"), v[0].categories)
        assertNotNull(v[1]); assertEquals("Aria", v[1].displayName)
        assertEquals(v.map { it.shortName }, EdgeVoice.parseList(EdgeVoice.toJson(v)).map { it.shortName })
    }
}
