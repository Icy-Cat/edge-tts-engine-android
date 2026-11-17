package io.github.icycat.edgetts.protocol

// 本文件含对 rany2/edge-tts 7.3.1 voices.py 语音列表字段的部分移植。
// 来源与 GNU LGPLv3 only 见 third_party/edge-tts/NOTICE.md。

import com.google.gson.JsonArray
import com.google.gson.JsonParser

/** voices/list 返回的一条语音（只保留需要的字段）。 */
data class EdgeVoice(
    val shortName: String,      // zh-CN-XiaoxiaoNeural，也是我们对 Android 暴露的 voice name
    val locale: String,         // zh-CN（注意：zh-CN-liaoning-* 的 Locale 仍是 zh-CN）
    val gender: String,
    val localeName: String,
    val friendlyName: String,
    val categories: List<String> = emptyList(),
    val personalities: List<String> = emptyList(),
) {
    /** "Xiaoxiao" / "liaoning-Xiaobei" */
    val displayName: String
        get() = shortName.removePrefix(locale + "-").removeSuffix("Neural")

    val language: String get() = locale.substringBefore('-')
    val region: String get() = locale.split('-').lastOrNull()?.takeIf { it != language } ?: ""

    companion object {
        fun parseList(json: String): List<EdgeVoice> {
            val arr: JsonArray = JsonParser.parseString(json).asJsonArray
            return arr.mapNotNull { el ->
                val o = el.asJsonObject
                val short = o.get("ShortName")?.asString ?: return@mapNotNull null
                val locale = o.get("Locale")?.asString ?: return@mapNotNull null
                val tag = o.getAsJsonObject("VoiceTag")
                EdgeVoice(
                    shortName = short,
                    locale = locale,
                    gender = o.get("Gender")?.asString ?: "",
                    localeName = o.get("LocaleName")?.asString ?: locale,
                    friendlyName = o.get("FriendlyName")?.asString ?: short,
                    categories = tag?.getAsJsonArray("ContentCategories")?.map { it.asString } ?: emptyList(),
                    personalities = tag?.getAsJsonArray("VoicePersonalities")?.map { it.asString } ?: emptyList(),
                )
            }
        }

        fun toJson(voices: List<EdgeVoice>): String {
            val arr = JsonArray()
            for (v in voices) {
                val o = com.google.gson.JsonObject()
                o.addProperty("ShortName", v.shortName)
                o.addProperty("Locale", v.locale)
                o.addProperty("Gender", v.gender)
                o.addProperty("LocaleName", v.localeName)
                o.addProperty("FriendlyName", v.friendlyName)
                arr.add(o)
            }
            return arr.toString()
        }
    }
}
