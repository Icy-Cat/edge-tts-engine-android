package io.github.icycat.edgetts

import android.content.Context
import io.github.icycat.edgetts.protocol.EdgeClient
import io.github.icycat.edgetts.protocol.EdgeVoice
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 语音列表：内存 → 本地缓存文件 → 打包在 assets 里的快照（离线也能回答 onIsLanguageAvailable）。
 * 联网刷新通过 [refresh]，一般每天最多一次。
 */
class VoiceRepository(context: Context, private val client: EdgeClient, private val prefs: EdgePrefs) {
    private val appContext = context.applicationContext
    private val cacheFile = File(appContext.filesDir, "voices.json")

    @Volatile
    private var cached: List<EdgeVoice>? = null
    private val refreshInFlight = AtomicBoolean(false)

    fun voices(): List<EdgeVoice> {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val list = loadFromDisk()
            cached = list
            return list
        }
    }

    private fun loadFromDisk(): List<EdgeVoice> {
        try {
            if (cacheFile.exists()) {
                val l = EdgeVoice.parseList(cacheFile.readText())
                if (l.isNotEmpty()) return l
            }
        } catch (_: Exception) {
        }
        return try {
            EdgeVoice.parseList(appContext.assets.open("voices.json").bufferedReader().use { it.readText() })
        } catch (e: Exception) {
            // 最后兜底：至少有默认语音，系统查询不至于崩溃
            listOf(EdgeVoice("zh-CN-XiaoxiaoNeural", "zh-CN", "Female", "Chinese (Mandarin, Simplified)", "Xiaoxiao"))
        }
    }

    /** 联网刷新；失败时保留旧列表并抛出异常。文件替换完成前不公布新列表。 */
    fun refresh(): List<EdgeVoice> {
        val fresh = client.listVoices()
        if (fresh.isEmpty()) throw IllegalStateException("voices/list returned empty list")
        synchronized(this) {
            AtomicFile.write(cacheFile, EdgeVoice.toJson(fresh))
            cached = fresh
            prefs.voicesFetchedAt = System.currentTimeMillis()
        }
        return fresh
    }

    /** 超过 [maxAgeMs] 就在后台线程刷新一次（忽略错误）。重叠调用只保留一个。 */
    fun refreshIfStale(maxAgeMs: Long = 24L * 3600 * 1000) {
        if (System.currentTimeMillis() - prefs.voicesFetchedAt < maxAgeMs) return
        if (!refreshInFlight.compareAndSet(false, true)) return
        Thread({
            try {
                refresh()
            } catch (_: Exception) {
            } finally {
                refreshInFlight.set(false)
            }
        }, "edge-voices-refresh").apply { isDaemon = true }.start()
    }

    fun byName(name: String?): EdgeVoice? = if (name == null) null else voices().firstOrNull { it.shortName == name }
}
