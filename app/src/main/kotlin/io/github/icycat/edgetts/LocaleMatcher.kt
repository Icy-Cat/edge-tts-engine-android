package io.github.icycat.edgetts

import io.github.icycat.edgetts.protocol.EdgeProtocol
import io.github.icycat.edgetts.protocol.EdgeVoice
import java.util.Locale

/**
 * Android TTS 框架传入的是 ISO 639-2/3 语言 + ISO 3166 alpha-3 国家（"zho"/"CHN"），
 * 而 Edge 用 "zh-CN"。这里统一转成 ISO-3 比较。纯 java.util，可在 JVM 单测。
 */
object LocaleMatcher {
    const val NOT_SUPPORTED = 0
    const val LANG_AVAILABLE = 1
    const val LANG_COUNTRY_AVAILABLE = 2

    fun iso3Language(code: String?): String {
        if (code.isNullOrEmpty()) return ""
        val c = code.lowercase(Locale.ROOT)
        if (c.length != 2) return c
        return try { Locale(c).isO3Language } catch (_: Exception) { c }
    }

    fun iso3Country(code: String?): String {
        if (code.isNullOrEmpty()) return ""
        val c = code.uppercase(Locale.ROOT)
        if (c.length != 2) return c
        return try { Locale("", c).isO3Country } catch (_: Exception) { c }
    }

    fun matchLevel(voices: List<EdgeVoice>, lang: String?, country: String?): Int {
        val l = iso3Language(lang)
        if (l.isEmpty()) return NOT_SUPPORTED
        val sameLang = voices.filter { iso3Language(it.language) == l }
        if (sameLang.isEmpty()) return NOT_SUPPORTED
        val c = iso3Country(country)
        if (c.isNotEmpty() && sameLang.any { iso3Country(it.region) == c }) return LANG_COUNTRY_AVAILABLE
        return LANG_AVAILABLE
    }

    /** 内置的"更合适的默认声音"，没有用户设置时使用。 */
    private val preferred = mapOf(
        "zh-CN" to "zh-CN-XiaoxiaoNeural",
        "zh-TW" to "zh-TW-HsiaoChenNeural",
        "zh-HK" to "zh-HK-HiuGaaiNeural",
        "en-US" to "en-US-AriaNeural",
        "en-GB" to "en-GB-SoniaNeural",
        "ja-JP" to "ja-JP-NanamiNeural",
        "ko-KR" to "ko-KR-SunHiNeural",
    )

    /**
     * 选择语音：显式 voiceName > 用户为该 locale 选的语音 > 内置偏好 > 该 locale 第一个。
     * 中文且未指定国家时使用 zh-CN。
     */
    fun pickVoice(
        voices: List<EdgeVoice>,
        lang: String?,
        country: String?,
        voiceName: String?,
        userChoice: (locale: String) -> String?,
    ): EdgeVoice? {
        if (!voiceName.isNullOrEmpty()) voices.firstOrNull { it.shortName == voiceName }?.let { return it }
        val l = iso3Language(lang)
        if (l.isEmpty()) return voices.firstOrNull { it.shortName == EdgeProtocol.DEFAULT_VOICE }
        val sameLang = voices.filter { iso3Language(it.language) == l }
        if (sameLang.isEmpty()) return null
        val c = iso3Country(country)
        var pool = if (c.isNotEmpty()) sameLang.filter { iso3Country(it.region) == c } else emptyList()
        if (pool.isEmpty()) {
            pool = if (l == "zho") sameLang.filter { it.locale == "zh-CN" }.ifEmpty { sameLang } else sameLang
        }
        val locale = pool.first().locale
        userChoice(locale)?.let { name -> pool.firstOrNull { it.shortName == name }?.let { return it } }
        preferred[locale]?.let { name -> pool.firstOrNull { it.shortName == name }?.let { return it } }
        return pool.first()
    }
}
