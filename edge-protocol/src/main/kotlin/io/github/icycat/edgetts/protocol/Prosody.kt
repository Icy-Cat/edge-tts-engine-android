package io.github.icycat.edgetts.protocol

/**
 * Android TTS 的 speechRate / pitch（100 = 正常）到 Edge SSML 的 rate(%) / pitch(Hz) 映射。
 */
object Prosody {
    /** Android speechRate（百分比，100 为 1.0x）+ 用户偏移百分点 -> "+25%" */
    fun rate(androidRate: Int, userOffsetPercent: Int = 0): String {
        val v = (androidRate - 100 + userOffsetPercent).coerceIn(-90, 200)
        return String.format(java.util.Locale.ROOT, "%+d%%", v)
    }

    /**
     * Android pitch（百分比，100 为正常）+ 用户偏移 Hz -> "+10Hz"。
     * 每偏离 100 一个百分点折算 0.5Hz，限制在 ±50Hz。
     */
    fun pitch(androidPitch: Int, userOffsetHz: Int = 0): String {
        val hz = ((androidPitch - 100) * 0.5).toInt() + userOffsetHz
        return String.format(java.util.Locale.ROOT, "%+dHz", hz.coerceIn(-50, 50))
    }
}
