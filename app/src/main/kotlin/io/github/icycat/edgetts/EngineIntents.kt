package io.github.icycat.edgetts

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech

/** CHECK_TTS_DATA：告诉调用方可用的语音（格式 "zho-CHN"）。联网引擎无需下载数据。 */
class CheckVoiceDataActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            val voices = EdgeEngine.get(this).voices.voices()
            val available = ArrayList(
                voices.map { "${LocaleMatcher.iso3Language(it.language)}-${LocaleMatcher.iso3Country(it.region)}" }.distinct()
            )
            val result = Intent().apply {
                putStringArrayListExtra(TextToSpeech.Engine.EXTRA_AVAILABLE_VOICES, available)
                putStringArrayListExtra(TextToSpeech.Engine.EXTRA_UNAVAILABLE_VOICES, ArrayList())
            }
            setResult(TextToSpeech.Engine.CHECK_VOICE_DATA_PASS, result)
        } catch (_: Throwable) {
            setResult(TextToSpeech.Engine.CHECK_VOICE_DATA_FAIL)
        }
        finish()
    }
}

/** GET_SAMPLE_TEXT：系统「听听看」按钮使用的示例文本。 */
class GetSampleTextActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val lang = try { intent?.getStringExtra("language") ?: "" } catch (_: Throwable) { "" }
        val text = when (try { LocaleMatcher.iso3Language(lang) } catch (_: Throwable) { "" }) {
            "zho" -> "你好，这是 Edge 语音引擎的示例语音。"
            "jpn" -> "こんにちは、これは Edge 音声エンジンのサンプルです。"
            else -> "Hello, this is a sample of the Edge text to speech engine."
        }
        setResult(TextToSpeech.LANG_AVAILABLE, Intent().putExtra("sampleText", text))
        finish()
    }
}
