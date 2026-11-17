package io.github.icycat.edgetts

import android.util.LruCache

/** 短文本合成结果的内存 LRU 缓存（按字节计容量）。 */
class PcmCache(maxBytes: Int = 6 * 1024 * 1024) {
    private val cache = object : LruCache<String, ByteArray>(maxBytes) {
        override fun sizeOf(key: String, value: ByteArray) = value.size
    }

    fun get(key: String): ByteArray? = cache.get(key)
    fun put(key: String, pcm: ByteArray) {
        if (pcm.isNotEmpty() && pcm.size < cache.maxSize() / 2) cache.put(key, pcm)
    }

    companion object {
        const val MAX_TEXT_CHARS = 400
        fun key(voice: String, rate: String, pitch: String, text: String) = "$voice|$rate|$pitch|$text"
    }
}
