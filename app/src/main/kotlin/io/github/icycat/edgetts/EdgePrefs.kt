package io.github.icycat.edgetts

import android.content.Context
import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 引擎设置与状态（SharedPreferences）。服务与设置页共用。 */
class EdgePrefs(context: Context) {
    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("edge_tts", Context.MODE_PRIVATE)

    /** 用户在设置里选的语音，key 为 Edge locale，如 zh-CN。 */
    fun voiceFor(locale: String): String? = sp.getString("voice.$locale", null)
    fun setVoiceFor(locale: String, shortName: String) = sp.edit().putString("voice.$locale", shortName).apply()

    /** 语速偏移（百分点，叠加到系统语速上），-50..+100。 */
    var rateOffset: Int
        get() = sp.getInt("rate_offset", 0)
        set(v) = sp.edit().putInt("rate_offset", v.coerceIn(-50, 100)).apply()

    /** 音调偏移（Hz），-50..+50。 */
    var pitchOffsetHz: Int
        get() = sp.getInt("pitch_offset_hz", 0)
        set(v) = sp.edit().putInt("pitch_offset_hz", v.coerceIn(-50, 50)).apply()

    var voicesFetchedAt: Long
        get() = sp.getLong("voices_fetched_at", 0L)
        set(v) = sp.edit().putLong("voices_fetched_at", v).apply()

    fun recordError(message: String) {
        sp.edit().putString("last_error", message).putLong("last_error_at", System.currentTimeMillis()).apply()
    }

    fun recordSuccess() {
        sp.edit().putLong("last_ok_at", System.currentTimeMillis()).apply()
    }

    fun statusText(): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        fun t(ms: Long) = if (ms == 0L) "—" else fmt.format(Date(ms))
        val err = sp.getString("last_error", null)
        return buildString {
            append("上次成功合成：").append(t(sp.getLong("last_ok_at", 0L))).append('\n')
            append("语音列表更新：").append(t(voicesFetchedAt)).append('\n')
            append("上次错误：")
            if (err == null) append("无") else append(t(sp.getLong("last_error_at", 0L))).append('\n').append(err)
        }
    }
}
