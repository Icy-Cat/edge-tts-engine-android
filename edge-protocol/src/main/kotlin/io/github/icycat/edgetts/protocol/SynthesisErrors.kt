package io.github.icycat.edgetts.protocol

import java.io.IOException
import java.net.SocketTimeoutException

/** 合成失败该怎么分类、要不要重试、以及可以写进界面的文字。不包含请求 URL。 */
enum class SynthesisFailureKind { TIMEOUT, NETWORK, SYNTHESIS, SERVICE }

object SynthesisErrors {
    fun isRetryable(e: Throwable): Boolean = when (e) {
        is EdgeException.Cancelled,
        is EdgeException.SkewAdjustment,
        is EdgeException.NoAudioReceived,
        is EdgeException.UnexpectedResponse,
        is EdgeException.UnknownResponse,
        -> false
        is EdgeException.HttpStatus -> e.code == 408 || e.code == 429 || e.code in 500..599
        is EdgeException.WebSocketError -> true
        is SocketTimeoutException -> true
        is IOException -> true
        else -> false
    }

    fun failureKind(e: Throwable): SynthesisFailureKind = when (e) {
        is SocketTimeoutException -> SynthesisFailureKind.TIMEOUT
        is EdgeException.WebSocketError ->
            if (e.cause is SocketTimeoutException) SynthesisFailureKind.TIMEOUT else SynthesisFailureKind.NETWORK
        is EdgeException.HttpStatus -> SynthesisFailureKind.NETWORK
        is EdgeException.NoAudioReceived,
        is EdgeException.UnexpectedResponse,
        is EdgeException.UnknownResponse,
        -> SynthesisFailureKind.SYNTHESIS
        is EdgeException.SkewAdjustment -> SynthesisFailureKind.SERVICE
        is EdgeException -> SynthesisFailureKind.NETWORK
        is IOException -> SynthesisFailureKind.NETWORK
        else -> SynthesisFailureKind.SERVICE
    }

    /** 给设置页和日志的说明。查询串和绝对 URL 会被抹掉。 */
    fun userMessage(e: Throwable): String {
        val raw = when (e) {
            is EdgeException.HttpStatus ->
                "HTTP ${e.code}（握手被拒。若持续出现 403，多半是 Sec-MS-GEC 版本或算法已经变化，见 README）"
            is EdgeException.NoAudioReceived -> "服务端没有返回音频（NoAudioReceived）"
            is EdgeException.SkewAdjustment -> "无法根据服务器时间校正本地时钟"
            is EdgeException.Cancelled -> "已取消"
            else -> "${e.javaClass.simpleName}: ${e.message.orEmpty()}"
        }
        return raw
            .replace(Regex("""(?i)\b(?:https?|wss?)://\S+"""), "[url]")
            .replace(Regex("""(?i)(Sec-MS-GEC(?:-Version)?=)[^&\s]+"""), "$1[redacted]")
    }
}
