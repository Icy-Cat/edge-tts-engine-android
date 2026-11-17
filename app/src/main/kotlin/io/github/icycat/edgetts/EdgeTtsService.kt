package io.github.icycat.edgetts

import android.media.AudioFormat
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.Voice
import android.util.Log
import io.github.icycat.edgetts.protocol.EdgeException
import io.github.icycat.edgetts.protocol.EdgeProtocol
import io.github.icycat.edgetts.protocol.EdgeVoice
import io.github.icycat.edgetts.protocol.Prosody
import io.github.icycat.edgetts.protocol.SynthesisErrors
import io.github.icycat.edgetts.protocol.SynthesisFailureKind
import java.io.ByteArrayOutputStream
import java.util.Locale
import kotlin.math.min

/**
 * 基于 Microsoft Edge「大声朗读」的系统 TTS 引擎。
 * 协议细节在 :edge-protocol 模块；这里只负责 Android 侧的适配。
 */
class EdgeTtsService : TextToSpeechService() {
    private val engine: EdgeEngine by lazy { EdgeEngine.get(this) }

    private val stops = StopGate()
    @Volatile private var loadedLang = "zho"
    @Volatile private var loadedCountry = "CHN"
    @Volatile private var loadedVariant = ""

    override fun onCreate() {
        // 注意：TextToSpeechService.onCreate() 内部会立刻回调 onLoadLanguage()，
        // 所以 engine 必须是惰性的，不能在 super.onCreate() 之后才赋值（v0.1.0 因此在服务创建时崩溃）。
        super.onCreate()
        try {
            engine.voices.refreshIfStale()
        } catch (e: Throwable) {
            Log.w(TAG, "refreshIfStale failed", e)
        }
    }

    /** 任何系统回调都不允许抛异常（同进程的设置页会被一起带崩）。 */
    private inline fun <T> safe(what: String, fallback: T, block: () -> T): T = try {
        block()
    } catch (e: Throwable) {
        Log.e(TAG, "$what failed", e)
        try { engine.prefs.recordError("$what: ${SynthesisErrors.userMessage(e)}") } catch (_: Throwable) { }
        fallback
    }

    private fun voiceList(): List<EdgeVoice> = engine.voices.voices()

    // ---- 语言 / 语音查询 ----

    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int =
        safe("onIsLanguageAvailable", TextToSpeech.LANG_NOT_SUPPORTED) {
            when (LocaleMatcher.matchLevel(voiceList(), lang, country)) {
                LocaleMatcher.LANG_COUNTRY_AVAILABLE -> TextToSpeech.LANG_COUNTRY_AVAILABLE
                LocaleMatcher.LANG_AVAILABLE -> TextToSpeech.LANG_AVAILABLE
                else -> TextToSpeech.LANG_NOT_SUPPORTED
            }
        }

    override fun onGetLanguage(): Array<String> = arrayOf(loadedLang, loadedCountry, loadedVariant)

    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int =
        safe("onLoadLanguage", TextToSpeech.LANG_NOT_SUPPORTED) {
            val r = onIsLanguageAvailable(lang, country, variant)
            if (r != TextToSpeech.LANG_NOT_SUPPORTED) {
                loadedLang = lang ?: ""
                loadedCountry = if (r == TextToSpeech.LANG_COUNTRY_AVAILABLE) country ?: "" else ""
                loadedVariant = ""
            }
            r
        }

    override fun onGetVoices(): List<Voice> = safe("onGetVoices", emptyList()) {
        voiceList().map { v ->
            Voice(
                v.shortName,
                Locale.forLanguageTag(v.locale),
                Voice.QUALITY_VERY_HIGH,
                Voice.LATENCY_NORMAL,
                true, // requiresNetworkConnection
                HashSet<String>(),
            )
        }
    }

    override fun onGetDefaultVoiceNameFor(lang: String?, country: String?, variant: String?): String? =
        safe<String?>("onGetDefaultVoiceNameFor", EdgeProtocol.DEFAULT_VOICE) { pick(lang, country, null)?.shortName }

    override fun onIsValidVoiceName(voiceName: String?): Int =
        safe("onIsValidVoiceName", TextToSpeech.ERROR) {
            if (engine.voices.byName(voiceName) != null) TextToSpeech.SUCCESS else TextToSpeech.ERROR
        }

    override fun onLoadVoice(voiceName: String?): Int = safe("onLoadVoice", TextToSpeech.ERROR) {
        val v = engine.voices.byName(voiceName) ?: return@safe TextToSpeech.ERROR
        loadedLang = LocaleMatcher.iso3Language(v.language)
        loadedCountry = LocaleMatcher.iso3Country(v.region)
        loadedVariant = ""
        TextToSpeech.SUCCESS
    }

    private fun pick(lang: String?, country: String?, voiceName: String?) =
        LocaleMatcher.pickVoice(voiceList(), lang, country, voiceName) { engine.prefs.voiceFor(it) }

    // ---- 合成 ----

    override fun onStop() {
        stops.stop()
    }

    override fun onSynthesizeText(request: SynthesisRequest, callback: SynthesisCallback) {
        try {
            synthesize(request, callback)
        } catch (e: Throwable) {
            Log.e(TAG, "onSynthesizeText failed: ${SynthesisErrors.userMessage(e)}")
            try { engine.prefs.recordError("合成前置步骤失败：${SynthesisErrors.userMessage(e)}") } catch (_: Throwable) { }
            try { callback.error(TextToSpeech.ERROR_SERVICE) } catch (_: Throwable) { }
        }
    }

    private fun synthesize(request: SynthesisRequest, callback: SynthesisCallback) {
        val token = stops.open()
        fun cancelled() = stops.isStopped(token)
        val text = request.charSequenceText?.toString() ?: ""
        val voice = (pick(request.language, request.country, request.voiceName)?.shortName)
            ?: EdgeProtocol.DEFAULT_VOICE
        val rate = Prosody.rate(request.speechRate, engine.prefs.rateOffset)
        val pitch = Prosody.pitch(request.pitch, engine.prefs.pitchOffsetHz)

        callback.start(EdgeProtocol.OUTPUT_SAMPLE_RATE, AudioFormat.ENCODING_PCM_16BIT, EdgeProtocol.OUTPUT_CHANNELS)
        if (cancelled()) return

        val cleaned = EdgeProtocol.removeIncompatibleCharacters(text)
        if (cleaned.none { Character.isLetterOrDigit(it) }) {
            callback.done() // 空白/纯标点：没有可读内容
            return
        }

        val cacheable = cleaned.length <= PcmCache.MAX_TEXT_CHARS
        val cacheKey = PcmCache.key(voice, rate, pitch, cleaned)
        if (cacheable) {
            engine.cache.get(cacheKey)?.let { pcm ->
                try {
                    emit(callback, pcm, 0, pcm.size, ::cancelled)
                    callback.done()
                } catch (_: EdgeException.Cancelled) {
                }
                return
            }
        }

        val capture = if (cacheable) ByteArrayOutputStream() else null
        try {
            val chunks = EdgeProtocol.splitTextByByteLength(
                EdgeProtocol.escapeXml(cleaned), EdgeProtocol.MAX_TEXT_BYTES
            )
            for (chunk in chunks) {
                if (cancelled()) return
                if (chunk.none { Character.isLetterOrDigit(it) }) continue
                synthesizeChunkWithRetry(chunk, voice, rate, pitch, callback, capture, ::cancelled)
            }
            if (cancelled()) return
            try {
                capture?.let { engine.cache.put(cacheKey, it.toByteArray()) }
            } catch (e: Throwable) {
                Log.w(TAG, "cache put failed: ${e.javaClass.simpleName}")
            }
            try {
                engine.prefs.recordSuccess()
            } catch (e: Throwable) {
                Log.w(TAG, "recordSuccess failed: ${e.javaClass.simpleName}")
            }
            callback.done()
        } catch (_: EdgeException.Cancelled) {
            // onStop：不再回报 error，避免把用户取消说成合成失败
        } catch (e: Throwable) {
            Log.w(TAG, "synthesis failed: ${SynthesisErrors.userMessage(e)}")
            try { engine.prefs.recordError(SynthesisErrors.userMessage(e)) } catch (_: Throwable) { }
            try { callback.error(errorCodeFor(e)) } catch (_: Throwable) { }
        }
    }

    /** 一个 SSML 请求；仅当还没有向系统送出任何音频时才重试（指数退避，最多 3 次）。 */
    private fun synthesizeChunkWithRetry(
        escapedChunk: String,
        voice: String,
        rate: String,
        pitch: String,
        callback: SynthesisCallback,
        capture: ByteArrayOutputStream?,
        cancelled: () -> Boolean,
    ) {
        val ssml = EdgeProtocol.mkssml(voice, escapedChunk, rate, pitch)
        var attempt = 0
        while (true) {
            var delivered = 0
            val partial = ByteArrayOutputStream()
            try {
                Mp3PcmDecoder { pcm ->
                    delivered += pcm.size
                    partial.write(pcm)
                    emit(callback, pcm, 0, pcm.size, cancelled)
                }.use { decoder ->
                    engine.client.synthesizeChunk(ssml, cancelled) { mp3 -> decoder.feed(mp3) }
                    decoder.finish()
                }
                capture?.write(partial.toByteArray())
                return
            } catch (e: EdgeException.Cancelled) {
                throw e
            } catch (e: Throwable) {
                attempt++
                if (cancelled()) throw EdgeException.Cancelled()
                if (delivered > 0 || attempt >= MAX_ATTEMPTS || !SynthesisErrors.isRetryable(e)) throw e
                val backoff = BASE_BACKOFF_MS shl (attempt - 1) // 500, 1000
                Log.w(TAG, "attempt $attempt failed, retry in ${backoff}ms")
                var waited = 0L
                while (waited < backoff) {
                    if (cancelled()) throw EdgeException.Cancelled()
                    Thread.sleep(50); waited += 50
                }
            }
        }
    }

    /** 把 PCM 切成不超过系统缓冲上限的偶数字节块；系统要求停止时抛 Cancelled。 */
    private fun emit(
        callback: SynthesisCallback,
        pcm: ByteArray,
        off: Int,
        len: Int,
        cancelled: () -> Boolean,
    ) {
        val max = callback.maxBufferSize.coerceAtLeast(1024) and 1.inv()
        var pos = off
        val end = off + len
        while (pos + 1 < end) {
            if (cancelled()) throw EdgeException.Cancelled()
            var n = min(max, end - pos) and 1.inv()
            if (n <= 0) break
            if (callback.audioAvailable(pcm, pos, n) != TextToSpeech.SUCCESS) throw EdgeException.Cancelled()
            pos += n
        }
    }

    private fun errorCodeFor(e: Throwable): Int = when (SynthesisErrors.failureKind(e)) {
        SynthesisFailureKind.TIMEOUT -> TextToSpeech.ERROR_NETWORK_TIMEOUT
        SynthesisFailureKind.NETWORK -> TextToSpeech.ERROR_NETWORK
        SynthesisFailureKind.SYNTHESIS -> TextToSpeech.ERROR_SYNTHESIS
        SynthesisFailureKind.SERVICE -> TextToSpeech.ERROR_SERVICE
    }

    companion object {
        private const val TAG = "EdgeTts"
        private const val MAX_ATTEMPTS = 3
        private const val BASE_BACKOFF_MS = 500L
    }
}
