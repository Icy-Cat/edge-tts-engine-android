package io.github.icycat.edgetts

import java.util.concurrent.atomic.AtomicInteger

/**
 * TextToSpeechService 可能在合成线程之外调用 onStop()。
 * 用递增代际代替“开始合成时把布尔标志清掉”：后一次 onStop 能取消当前这次，
 * 又不会把上一次停止留下来，误伤下一次合成。
 *
 * 在 open() 之前到达的 onStop 不会被这次 open() 看到。那种时序要靠
 * SynthesisCallback.audioAvailable 返回错误来停住，避免把上一次停止算到下一次上。
 */
class StopGate {
    private val epoch = AtomicInteger(0)

    fun stop() {
        epoch.incrementAndGet()
    }

    fun open(): Int = epoch.get()

    fun isStopped(token: Int): Boolean = epoch.get() != token
}
