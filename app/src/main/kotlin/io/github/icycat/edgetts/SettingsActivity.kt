package io.github.icycat.edgetts

import android.app.Activity
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.util.Log
import android.speech.tts.UtteranceProgressListener
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import io.github.icycat.edgetts.protocol.EdgeVoice
import io.github.icycat.edgetts.protocol.SynthesisErrors

/** 引擎设置：默认语音（zh-CN 默认 Xiaoxiao）、语速/音调偏移、试听、状态。 */
class SettingsActivity : Activity() {
    private lateinit var engine: EdgeEngine
    private lateinit var spLocale: Spinner
    private lateinit var spVoice: Spinner
    private lateinit var tvStatus: TextView
    private lateinit var etText: EditText

    private var localeList: List<Pair<String, String>> = emptyList() // (locale, label)
    private var voiceList: List<EdgeVoice> = emptyList()
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingSpeak = false
    private var lastTestStatus = ""
    private var suppressVoiceSave = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        spLocale = findViewById(R.id.spLocale)
        spVoice = findViewById(R.id.spVoice)
        tvStatus = findViewById(R.id.tvStatus)
        etText = findViewById(R.id.etText)
        val debuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (debuggable) findViewById<TextView>(R.id.tvDebugSigning).visibility = View.VISIBLE
        findViewById<Button>(R.id.btnPrivacy).setOnClickListener {
            openNotice("legal/privacy.txt", getString(R.string.open_privacy))
        }
        findViewById<Button>(R.id.btnLgpl).setOnClickListener {
            openNotice("legal/lgpl-3.0.txt", getString(R.string.open_lgpl))
        }
        findViewById<Button>(R.id.btnGpl).setOnClickListener {
            openNotice("legal/gpl-3.0.txt", getString(R.string.open_gpl))
        }

        // 设置页启动绝不能崩：任何一步失败都只在状态栏显示错误。
        try {
            engine = EdgeEngine.get(this)
            engineReady = true
        } catch (e: Throwable) {
            fail("初始化失败", e)
            return
        }

        findViewById<Button>(R.id.btnSystemTts).setOnClickListener {
            try { startActivity(Intent("com.android.settings.TTS_SETTINGS")) }
            catch (_: Throwable) {
                try { startActivity(Intent(Settings.ACTION_SETTINGS)) } catch (e: Throwable) { fail("无法打开系统设置", e) }
            }
        }
        findViewById<Button>(R.id.btnTest).setOnClickListener { guarded("试听") { testSpeak() } }
        findViewById<Button>(R.id.btnStop).setOnClickListener { guarded("停止") { tts?.stop() } }
        findViewById<Button>(R.id.btnRefresh).setOnClickListener { guarded("刷新") { refreshVoices() } }

        guarded("初始化语速/音调") { setupSeekBars() }
        guarded("加载语音列表") { populateLocales(selectLocale = "zh-CN") }
        refreshStatus()
        // adb 调试入口只在 debuggable 构建生效，避免任意应用拉起正式包装来出声。
        if (debuggable && intent?.getBooleanExtra("auto_speak", false) == true) {
            tvStatus.post { guarded("试听") { testSpeak() } }
        }
    }

    private fun openNotice(asset: String, title: String) {
        startActivity(Intent(this, LegalNoticeActivity::class.java)
            .putExtra(LegalNoticeActivity.EXTRA_ASSET, asset)
            .putExtra(LegalNoticeActivity.EXTRA_TITLE, title))
    }

    private var engineReady = false

    /** 记录并显示错误，不抛出。 */
    private fun fail(what: String, e: Throwable) {
        Log.e("EdgeTtsSettings", what, e)
        lastTestStatus = "$what：${SynthesisErrors.userMessage(e)}"
        try { if (engineReady) engine.prefs.recordError(lastTestStatus) } catch (_: Throwable) { }
        try { tvStatus.text = lastTestStatus + if (engineReady) "\n\n" + engine.prefs.statusText() else "" } catch (_: Throwable) { }
    }

    private inline fun guarded(what: String, block: () -> Unit) {
        try { block() } catch (e: Throwable) { fail("$what 失败", e) }
    }

    override fun onResume() {
        super.onResume()
        if (engineReady) guarded("刷新状态") { refreshStatus() }
    }

    override fun onDestroy() {
        try { tts?.shutdown() } catch (_: Throwable) { }
        tts = null
        super.onDestroy()
    }

    // ---- 语音选择 ----

    private fun populateLocales(selectLocale: String) {
        val all = engine.voices.voices()
        val byLocale = all.groupBy { it.locale }
        localeList = byLocale.keys
            .sortedWith(compareBy<String> { if (it == "zh-CN") 0 else if (it.startsWith("zh")) 1 else if (it == "en-US") 2 else if (it.startsWith("en")) 3 else 4 }.thenBy { it })
            .map { it to "$it  ${byLocale[it]!!.first().localeName}" }
        spLocale.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, localeList.map { it.second })
        spLocale.setSelection(localeList.indexOfFirst { it.first == selectLocale }.coerceAtLeast(0), false)
        spLocale.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                localeList.getOrNull(pos)?.let { guarded("切换语言") { populateVoices(it.first) } }
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        localeList.getOrNull(spLocale.selectedItemPosition)?.let { populateVoices(it.first) }
    }

    private fun populateVoices(locale: String) {
        suppressVoiceSave = true
        voiceList = engine.voices.voices().filter { it.locale == locale }
        spVoice.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            voiceList.map { "${it.displayName}（${if (it.gender == "Female") "女" else if (it.gender == "Male") "男" else it.gender}）" },
        )
        val saved = engine.prefs.voiceFor(locale)
            ?: LocaleMatcher.pickVoice(engine.voices.voices(), locale.substringBefore('-'), locale.substringAfterLast('-'), null) { null }?.shortName
        spVoice.setSelection(voiceList.indexOfFirst { it.shortName == saved }.coerceAtLeast(0), false)
        spVoice.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (suppressVoiceSave) { suppressVoiceSave = false; return }
                voiceList.getOrNull(pos)?.let { engine.prefs.setVoiceFor(locale, it.shortName) }
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        suppressVoiceSave = false
        etText.setText(sampleText(locale))
    }

    private fun sampleText(locale: String) = when {
        locale.startsWith("zh") -> "你好，这是 Edge 语音引擎的试听。今天天气不错。"
        locale.startsWith("ja") -> "こんにちは、これは Edge 音声エンジンのテストです。"
        else -> "Hello, this is a test of the Edge text to speech engine."
    }

    private fun currentVoice(): EdgeVoice? = voiceList.getOrNull(spVoice.selectedItemPosition)

    // ---- 语速 / 音调 ----

    private fun setupSeekBars() {
        val sbRate = findViewById<SeekBar>(R.id.sbRate)
        val sbPitch = findViewById<SeekBar>(R.id.sbPitch)
        val tvRate = findViewById<TextView>(R.id.tvRate)
        val tvPitch = findViewById<TextView>(R.id.tvPitch)
        fun labels() {
            tvRate.text = "语速偏移：%+d%%（叠加在系统语速上）".format(sbRate.progress - 50)
            tvPitch.text = "音调偏移：%+d Hz（叠加在系统音调上）".format(sbPitch.progress - 50)
        }
        sbRate.progress = engine.prefs.rateOffset + 50
        sbPitch.progress = engine.prefs.pitchOffsetHz + 50
        labels()
        val l = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                labels()
                if (!fromUser) return
                if (s === sbRate) engine.prefs.rateOffset = p - 50 else engine.prefs.pitchOffsetHz = p - 50
            }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        }
        sbRate.setOnSeekBarChangeListener(l)
        sbPitch.setOnSeekBarChangeListener(l)
    }

    // ---- 试听 / 刷新 ----

    private fun testSpeak() {
        val voice = currentVoice() ?: return
        engine.prefs.setVoiceFor(voice.locale, voice.shortName)
        lastTestStatus = "试听：正在连接 Edge…"
        refreshStatus()
        if (tts == null) {
            pendingSpeak = true
            tts = TextToSpeech(applicationContext, { status ->
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    if (status == TextToSpeech.SUCCESS) guarded("试听") {
                        ttsReady = true
                        tts?.setOnUtteranceProgressListener(progressListener)
                        if (pendingSpeak) { pendingSpeak = false; speakNow() }
                    } else {
                        lastTestStatus = "试听失败：无法初始化 TextToSpeech（引擎未被系统识别？status=$status）"
                        val failed = tts
                        tts = null
                        try { failed?.shutdown() } catch (_: Throwable) { }
                        refreshStatus()
                    }
                }
            }, packageName)
        } else if (ttsReady) speakNow()
    }

    private fun speakNow() {
        val t = tts ?: return
        val voice = currentVoice() ?: return
        // 通过系统 TextToSpeech 走完整的 Service 路径，而不是直接调用协议层
        val androidVoice = t.voices?.firstOrNull { it.name == voice.shortName }
        if (androidVoice != null) t.voice = androidVoice
        val r = t.speak(etText.text.toString(), TextToSpeech.QUEUE_FLUSH, null, "settings-test")
        if (r != TextToSpeech.SUCCESS) {
            lastTestStatus = "试听失败：speak() 返回 $r"
            refreshStatus()
        }
    }

    private val progressListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) = post("试听：播放中…")
        override fun onDone(utteranceId: String?) = post("试听：完成")
        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) = post("试听：失败（见上次错误）")
        override fun onError(utteranceId: String?, errorCode: Int) = post("试听：失败，错误码 $errorCode（见上次错误）")
        private fun post(msg: String) = runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            lastTestStatus = msg
            guarded("刷新状态") { refreshStatus() }
        }
    }

    private fun refreshVoices() {
        lastTestStatus = "正在刷新语音列表…"
        refreshStatus()
        val worker = Thread {
            val msg = try {
                val n = engine.voices.refresh().size
                "语音列表已更新：$n 个语音"
            } catch (e: Throwable) {
                val detail = SynthesisErrors.userMessage(e)
                engine.prefs.recordError("刷新语音列表失败：$detail")
                "刷新失败：$detail"
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                lastTestStatus = msg
                guarded("刷新语音列表") {
                    val keep = localeList.getOrNull(spLocale.selectedItemPosition)?.first ?: "zh-CN"
                    populateLocales(keep)
                    refreshStatus()
                }
            }
        }
        worker.isDaemon = true
        worker.start()
    }

    private fun refreshStatus() {
        tvStatus.text = (if (lastTestStatus.isEmpty()) "" else lastTestStatus + "\n\n") + engine.prefs.statusText()
    }
}
