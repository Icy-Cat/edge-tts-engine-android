package io.github.icycat.edgetts.protocol

// 本文件含对 rany2/edge-tts 7.3.1 的修改移植。来源与 GNU LGPLv3 only 见
// third_party/edge-tts/NOTICE.md。该声明不给 app/ 的独立 Android 代码授予许可证。

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * Microsoft Edge "Read Aloud" 协议的纯函数实现（无 IO，无 Android 依赖），
 * 逐项移植自 rany2/edge-tts 7.2.8：constants.py / drm.py / communicate.py / voices.py。
 *
 * 微软改动协议时，通常只需要更新本对象里的常量（见 README「更新版本常量」）。
 */
object EdgeProtocol {
    // ---- constants.py ----
    const val BASE_URL = "speech.platform.bing.com/consumer/speech/synthesize/readaloud"
    const val TRUSTED_CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
    const val WSS_URL = "wss://$BASE_URL/edge/v1?TrustedClientToken=$TRUSTED_CLIENT_TOKEN"
    const val VOICE_LIST_URL = "https://$BASE_URL/voices/list?trustedclienttoken=$TRUSTED_CLIENT_TOKEN"

    const val DEFAULT_VOICE = "zh-CN-XiaoxiaoNeural"

    /** Edge 完整版本号。每次 edge-tts 发新版通常只改这一行。 */
    const val CHROMIUM_FULL_VERSION = "143.0.3650.75"
    val CHROMIUM_MAJOR_VERSION: String get() = CHROMIUM_FULL_VERSION.substringBefore('.')

    /** 当前 edge-tts 使用 `2-<完整版本>`。`1-` 是已经失效的旧前缀。 */
    val SEC_MS_GEC_VERSION: String get() = "2-$CHROMIUM_FULL_VERSION"

    /** 请求的输出格式：24kHz / 48kbps CBR / 单声道 MP3（MediaCodec 可直接解码）。 */
    const val OUTPUT_FORMAT = "audio-24khz-48kbitrate-mono-mp3"
    const val OUTPUT_SAMPLE_RATE = 24000
    const val OUTPUT_CHANNELS = 1

    /** 单次 SSML 请求的最大（转义后）文本字节数。 */
    const val MAX_TEXT_BYTES = 4096

    // 注意：参考实现还发送 Accept-Encoding: gzip, deflate, br, zstd。
    // OkHttp 只在自己添加 Accept-Encoding 时才会自动解压 gzip，所以这里故意不手动设置，
    // 否则语音列表可能收到无法解码的 br/zstd 响应。
    private val userAgent: String
        get() = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36" +
            " (KHTML, like Gecko) Chrome/$CHROMIUM_MAJOR_VERSION.0.0.0 Safari/537.36" +
            " Edg/$CHROMIUM_MAJOR_VERSION.0.0.0"

    /** WSS_HEADERS（Sec-WebSocket-Version 由 OkHttp 自己写入 13）。 */
    fun wssHeaders(muid: String = generateMuid()): Map<String, String> = linkedMapOf(
        "Pragma" to "no-cache",
        "Cache-Control" to "no-cache",
        "Origin" to "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold",
        "User-Agent" to userAgent,
        "Accept-Language" to "en-US,en;q=0.9",
        "Cookie" to "muid=$muid;",
    )

    /** VOICE_HEADERS */
    fun voiceHeaders(muid: String = generateMuid()): Map<String, String> = linkedMapOf(
        "Authority" to "speech.platform.bing.com",
        "Sec-CH-UA" to "\" Not;A Brand\";v=\"99\", \"Microsoft Edge\";v=\"$CHROMIUM_MAJOR_VERSION\"," +
            " \"Chromium\";v=\"$CHROMIUM_MAJOR_VERSION\"",
        "Sec-CH-UA-Mobile" to "?0",
        "Accept" to "*/*",
        "Sec-Fetch-Site" to "none",
        "Sec-Fetch-Mode" to "cors",
        "Sec-Fetch-Dest" to "empty",
        "User-Agent" to userAgent,
        "Accept-Language" to "en-US,en;q=0.9",
        "Cookie" to "muid=$muid;",
    )

    // ---- drm.py ----

    /**
     * Sec-MS-GEC v2。时间先向下取整到 5 分钟，摘要算法在 [SecMsGec]。
     *
     * @param unixSeconds 已含时钟偏差修正的 Unix 时间（秒）
     */
    fun secMsGec(unixSeconds: Double): String = SecMsGec.token(unixSeconds)

    fun generateMuid(): String {
        val bytes = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
        return bytes.joinToString("") { String.format(Locale.ROOT, "%02X", it) }
    }

    /** 解析 RFC 2616 / 1123 的 Date 头（"Fri, 02 Oct 2026 15:14:15 GMT"），失败返回 null。 */
    fun parseRfc2616Date(date: String): Double? = try {
        val fmt = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        fmt.isLenient = false
        fmt.parse(date.trim())!!.time / 1000.0
    } catch (e: Exception) {
        null
    }

    fun secMsGecUrlParams(unixSeconds: Double): String =
        "&Sec-MS-GEC=${secMsGec(unixSeconds)}&Sec-MS-GEC-Version=$SEC_MS_GEC_VERSION"

    // ---- communicate.py ----

    fun connectId(): String = UUID.randomUUID().toString().replace("-", "")

    fun wssUrl(unixSeconds: Double, connectionId: String = connectId()): String =
        "$WSS_URL&ConnectionId=$connectionId${secMsGecUrlParams(unixSeconds)}"

    fun voiceListUrl(unixSeconds: Double): String = VOICE_LIST_URL + secMsGecUrlParams(unixSeconds)

    /** JavaScript 风格时间戳，如 "Fri Oct 02 2026 15:14:15 GMT+0000 (Coordinated Universal Time)"。 */
    fun dateToString(now: Date = Date()): String {
        val fmt = SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(now) + " GMT+0000 (Coordinated Universal Time)"
    }

    /** speech.config 文本帧。 */
    fun speechConfigMessage(timestamp: String = dateToString()): String =
        "X-Timestamp:$timestamp\r\n" +
            "Content-Type:application/json; charset=utf-8\r\n" +
            "Path:speech.config\r\n\r\n" +
            "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":{" +
            "\"sentenceBoundaryEnabled\":\"true\",\"wordBoundaryEnabled\":\"false\"}," +
            "\"outputFormat\":\"$OUTPUT_FORMAT\"}}}}\r\n"

    /** ssml 文本帧。注意 X-Timestamp 末尾的 "Z" 是 Edge 自己的 bug，必须保留。 */
    fun ssmlMessage(requestId: String, timestamp: String, ssml: String): String =
        "X-RequestId:$requestId\r\n" +
            "Content-Type:application/ssml+xml\r\n" +
            "X-Timestamp:${timestamp}Z\r\n" +
            "Path:ssml\r\n\r\n" +
            ssml

    /**
     * 构造 SSML。[voice] 可以是短名 (zh-CN-XiaoxiaoNeural) 或完整名，会按 Edge 的做法转成完整名；
     * [escapedText] 必须已经做过 XML 转义（见 [escapeXml]）。
     */
    fun mkssml(
        voice: String,
        escapedText: String,
        rate: String = "+0%",
        pitch: String = "+0Hz",
        volume: String = "+0%",
    ): String {
        require(RATE_RE.matches(rate)) { "Invalid rate '$rate'." }
        require(RATE_RE.matches(volume)) { "Invalid volume '$volume'." }
        require(PITCH_RE.matches(pitch)) { "Invalid pitch '$pitch'." }
        val voiceName = fullVoiceName(voice)
        require(voiceName.none { it == '\'' || it == '"' || it == '<' || it == '>' || it == '&' }) {
            "Invalid voice name"
        }
        return "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='en-US'>" +
            "<voice name='$voiceName'>" +
            "<prosody pitch='$pitch' rate='$rate' volume='$volume'>" +
            escapedText +
            "</prosody></voice></speak>"
    }

    private val RATE_RE = Regex("^[+-]\\d+%$")
    private val PITCH_RE = Regex("^[+-]\\d+Hz$")
    private val SHORT_NAME_RE = Regex("^([a-z]{2,})-([A-Z]{2,})-(.+Neural)$")

    /**
     * data_classes.py TTSConfig.__post_init__：
     * "zh-CN-XiaoxiaoNeural" -> "Microsoft Server Speech Text to Speech Voice (zh-CN, XiaoxiaoNeural)"，
     * "zh-CN-liaoning-XiaobeiNeural" -> "... (zh-CN-liaoning, XiaobeiNeural)"。
     */
    fun fullVoiceName(voice: String): String {
        val m = SHORT_NAME_RE.matchEntire(voice) ?: return voice
        val lang = m.groupValues[1]
        var region = m.groupValues[2]
        var name = m.groupValues[3]
        val dash = name.indexOf('-')
        if (dash != -1) {
            region = "$region-${name.substring(0, dash)}"
            name = name.substring(dash + 1)
        }
        return "Microsoft Server Speech Text to Speech Voice ($lang-$region, $name)"
    }

    /** xml.sax.saxutils.escape：只转义 & < >。 */
    fun escapeXml(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    /** 服务端不支持的控制字符（垂直制表符等）替换为空格。 */
    fun removeIncompatibleCharacters(s: String): String {
        val sb = StringBuilder(s.length)
        for (ch in s) {
            val c = ch.code
            sb.append(if (c in 0..8 || c in 11..12 || c in 14..31) ' ' else ch)
        }
        return sb.toString()
    }

    /**
     * 按 UTF-8 字节数切分（对应 split_text_by_byte_length），保证：
     * 1) 每块 ≤ [byteLength] 字节；2) 不切断多字节字符/代理对；3) 不切断 &amp; 之类的实体。
     * 切点优先级：换行 > 句末标点（。！？；!?;…）> 空格 > 硬切。
     *
     * 与参考实现的差异：参考实现在没有空格/换行时（典型的中文长文本）不会限制块大小，
     * 这里会按字节预算硬切，并额外优先在句末标点处断开，以降低首包延迟。
     */
    fun splitTextByByteLength(text: String, byteLength: Int = MAX_TEXT_BYTES): List<String> {
        require(byteLength >= 8) { "byteLength too small" }
        val out = ArrayList<String>()
        var rest = text
        while (utf8Length(rest) > byteLength) {
            rest = rest.trimStart()
            val limitChars = charsWithinBytes(rest, byteLength)
            var cut = rest.lastIndexOf('\n', limitChars - 1)
            if (cut < 0) {
                val e = lastSentenceEnd(rest, limitChars)
                if (e >= 0) cut = e + 1
            }
            if (cut < 0) cut = rest.lastIndexOf(' ', limitChars - 1)
            if (cut <= 0) cut = limitChars
            cut = adjustForXmlEntity(rest, cut)
            if (cut <= 0) cut = limitChars // 实体异常长，放弃保护
            val chunk = rest.substring(0, cut).trim()
            if (chunk.isNotEmpty()) out += chunk
            rest = rest.substring(cut)
        }
        val tail = rest.trim()
        if (tail.isNotEmpty()) out += tail
        return out
    }

    private const val SENTENCE_ENDS = "。！？；!?;…"

    private fun lastSentenceEnd(s: String, limitChars: Int): Int {
        for (i in limitChars - 1 downTo 0) if (s[i] in SENTENCE_ENDS) return i
        return -1
    }

    private fun utf8Length(s: String): Int {
        var n = 0
        var i = 0
        while (i < s.length) {
            val c = s[i]
            n += when {
                c.code < 0x80 -> 1
                c.code < 0x800 -> 2
                Character.isHighSurrogate(c) && i + 1 < s.length -> { i++; 4 }
                else -> 3
            }
            i++
        }
        return n
    }

    /** 最大的字符数 n，使 s[0,n) 的 UTF-8 长度 ≤ bytes（不拆代理对）。 */
    private fun charsWithinBytes(s: String, bytes: Int): Int {
        var n = 0
        var i = 0
        while (i < s.length) {
            val c = s[i]
            val (len, step) = when {
                c.code < 0x80 -> 1 to 1
                c.code < 0x800 -> 2 to 1
                Character.isHighSurrogate(c) && i + 1 < s.length -> 4 to 2
                else -> 3 to 1
            }
            if (n + len > bytes) return i
            n += len
            i += step
        }
        return s.length
    }

    private fun adjustForXmlEntity(s: String, splitAt: Int): Int {
        var at = splitAt
        while (at > 0) {
            val amp = s.lastIndexOf('&', at - 1)
            if (amp < 0) break
            if (s.indexOf(';', amp).let { it in 0 until at }) break
            at = amp
        }
        return at
    }

    // ---- 消息解析（communicate.py get_headers_and_data & __stream） ----

    class TextMessage(val headers: Map<String, String>, val body: String) {
        val path: String? get() = headers["Path"]
    }

    /** 二进制帧：前 2 字节大端为头长度，随后是头，再之后是负载。 */
    class BinaryMessage(val headers: Map<String, String>, val data: ByteArray) {
        val path: String? get() = headers["Path"]
        val contentType: String? get() = headers["Content-Type"]
    }

    fun parseTextMessage(text: String): TextMessage {
        val idx = text.indexOf("\r\n\r\n")
        val head = if (idx >= 0) text.substring(0, idx) else text
        val body = if (idx >= 0) text.substring(idx + 4) else ""
        return TextMessage(parseHeaderLines(head), body)
    }

    fun parseBinaryMessage(data: ByteArray): BinaryMessage {
        if (data.size < 2) throw EdgeException.UnexpectedResponse(
            "We received a binary message, but it is missing the header length."
        )
        val headerLength = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
        if (headerLength + 2 > data.size) throw EdgeException.UnexpectedResponse(
            "The header length is greater than the length of the data."
        )
        val head = String(data, 2, headerLength, Charsets.UTF_8)
        return BinaryMessage(parseHeaderLines(head), data.copyOfRange(2 + headerLength, data.size))
    }

    private fun parseHeaderLines(head: String): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        for (line in head.split("\r\n")) {
            if (line.isEmpty()) continue
            val i = line.indexOf(':')
            if (i <= 0) continue
            map[line.substring(0, i)] = line.substring(i + 1)
        }
        return map
    }
}

/** 移植自 edge_tts/exceptions.py。 */
sealed class EdgeException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class NoAudioReceived(message: String = "No audio was received. Please verify that your parameters are correct.") : EdgeException(message)
    class UnexpectedResponse(message: String) : EdgeException(message)
    class UnknownResponse(message: String) : EdgeException(message)
    class WebSocketError(message: String, cause: Throwable? = null) : EdgeException(message, cause)
    class SkewAdjustment(message: String) : EdgeException(message)

    /** WebSocket / HTTP 握手未返回 101 / 200。403 时 [serverDate] 用于时钟校正。 */
    class HttpStatus(val code: Int, val serverDate: String?, message: String) : EdgeException(message)
    class Cancelled : EdgeException("cancelled")
}
