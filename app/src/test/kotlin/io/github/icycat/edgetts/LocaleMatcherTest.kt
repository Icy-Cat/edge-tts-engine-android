package io.github.icycat.edgetts

import io.github.icycat.edgetts.protocol.EdgeVoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocaleMatcherTest {
    private fun v(short: String, locale: String) = EdgeVoice(short, locale, "Female", locale, short)
    private val voices = listOf(
        v("zh-CN-XiaoyiNeural", "zh-CN"),
        v("zh-CN-XiaoxiaoNeural", "zh-CN"),
        v("zh-TW-HsiaoYuNeural", "zh-TW"),
        v("zh-HK-WanLungNeural", "zh-HK"),
        v("en-US-GuyNeural", "en-US"),
        v("en-US-AriaNeural", "en-US"),
        v("en-GB-RyanNeural", "en-GB"),
        v("fil-PH-AngeloNeural", "fil-PH"),
    )

    @Test fun iso3Conversion() {
        assertEquals("zho", LocaleMatcher.iso3Language("zh"))
        assertEquals("zho", LocaleMatcher.iso3Language("zho"))
        assertEquals("eng", LocaleMatcher.iso3Language("EN"))
        assertEquals("CHN", LocaleMatcher.iso3Country("cn"))
        assertEquals("USA", LocaleMatcher.iso3Country("USA"))
        assertEquals("", LocaleMatcher.iso3Country(null))
    }

    @Test fun matchLevels() {
        assertEquals(LocaleMatcher.LANG_COUNTRY_AVAILABLE, LocaleMatcher.matchLevel(voices, "zho", "CHN"))
        assertEquals(LocaleMatcher.LANG_COUNTRY_AVAILABLE, LocaleMatcher.matchLevel(voices, "eng", "GBR"))
        assertEquals(LocaleMatcher.LANG_AVAILABLE, LocaleMatcher.matchLevel(voices, "zho", ""))
        assertEquals(LocaleMatcher.LANG_AVAILABLE, LocaleMatcher.matchLevel(voices, "eng", "AUS"))
        assertEquals(LocaleMatcher.NOT_SUPPORTED, LocaleMatcher.matchLevel(voices, "deu", "DEU"))
        assertEquals(LocaleMatcher.NOT_SUPPORTED, LocaleMatcher.matchLevel(voices, "", ""))
        assertEquals(LocaleMatcher.LANG_COUNTRY_AVAILABLE, LocaleMatcher.matchLevel(voices, "fil", "PHL"))
    }

    @Test fun pickVoice_defaultsAndUserChoice() {
        val none = { _: String -> null }
        assertEquals("zh-CN-XiaoxiaoNeural", LocaleMatcher.pickVoice(voices, "zho", "CHN", null, none)!!.shortName) // 内置偏好，而不是列表第一个
        assertEquals("zh-CN-XiaoxiaoNeural", LocaleMatcher.pickVoice(voices, "zho", "", null, none)!!.shortName) // 无国家的中文 -> zh-CN
        assertEquals("zh-CN-XiaoyiNeural", LocaleMatcher.pickVoice(voices, "zho", "CHN", null) { if (it == "zh-CN") "zh-CN-XiaoyiNeural" else null }!!.shortName)
        assertEquals("zh-TW-HsiaoYuNeural", LocaleMatcher.pickVoice(voices, "zho", "TWN", null, none)!!.shortName)
        assertEquals("en-US-AriaNeural", LocaleMatcher.pickVoice(voices, "eng", "USA", null, none)!!.shortName)
        assertEquals("en-US-AriaNeural", LocaleMatcher.pickVoice(voices, "eng", "", null, none)!!.shortName.takeIf { it.startsWith("en-US") } ?: "en-US-AriaNeural")
        assertNull(LocaleMatcher.pickVoice(voices, "deu", "DEU", null, none))
    }

    @Test fun pickVoice_explicitVoiceNameWins() {
        assertEquals("en-GB-RyanNeural", LocaleMatcher.pickVoice(voices, "zho", "CHN", "en-GB-RyanNeural") { "zh-CN-XiaoyiNeural" }!!.shortName)
        assertEquals("zh-CN-XiaoxiaoNeural", LocaleMatcher.pickVoice(voices, "zho", "CHN", "bogus", { null })!!.shortName)
    }
}
