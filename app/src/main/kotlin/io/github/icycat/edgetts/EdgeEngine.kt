package io.github.icycat.edgetts

import android.content.Context
import io.github.icycat.edgetts.protocol.EdgeClient

/** 进程内共享的单例：同一个 EdgeClient（共享时钟偏差）、语音列表、设置、缓存。 */
class EdgeEngine private constructor(context: Context) {
    val prefs = EdgePrefs(context)
    val client = EdgeClient()
    val voices = VoiceRepository(context, client, prefs)
    val cache = PcmCache()

    companion object {
        @Volatile private var instance: EdgeEngine? = null
        fun get(context: Context): EdgeEngine =
            instance ?: synchronized(this) { instance ?: EdgeEngine(context.applicationContext).also { instance = it } }
    }
}
