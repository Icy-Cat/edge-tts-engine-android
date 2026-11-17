package io.github.icycat.edgetts.protocol

// 本文件含对 rany2/edge-tts 7.3.1 drm.py 时钟偏差逻辑的修改移植。
// 来源与 GNU LGPLv3 only 见 third_party/edge-tts/NOTICE.md。

/**
 * drm.py 的 DRM 类：带时钟偏差修正的 Sec-MS-GEC 生成。
 * 偏差在同一进程内累计（与参考实现的类变量一致）。
 */
class Drm(private val clockMillis: () -> Long = System::currentTimeMillis) {
    @Volatile
    var clockSkewSeconds: Double = 0.0
        private set

    fun adjClockSkewSeconds(skew: Double) {
        synchronized(this) { clockSkewSeconds += skew }
    }

    fun unixTimestamp(): Double = synchronized(this) { clockMillis() / 1000.0 + clockSkewSeconds }

    fun secMsGec(): String = EdgeProtocol.secMsGec(unixTimestamp())

    /**
     * 403 时用服务器 Date 头修正本地时钟（handle_client_response_error）。
     * 读当前偏差、计算增量和写回在同一把锁里，避免并发 403 把偏差加上两次。
     * @throws EdgeException.SkewAdjustment 没有或无法解析 Date
     */
    fun handleServerDate(serverDate: String?) {
        if (serverDate == null) throw EdgeException.SkewAdjustment("No server date in headers.")
        val parsed = EdgeProtocol.parseRfc2616Date(serverDate)
            ?: throw EdgeException.SkewAdjustment("Failed to parse server date.")
        synchronized(this) {
            val now = clockMillis() / 1000.0 + clockSkewSeconds
            clockSkewSeconds += parsed - now
        }
    }
}
