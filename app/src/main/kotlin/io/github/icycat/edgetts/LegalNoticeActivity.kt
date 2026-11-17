package io.github.icycat.edgetts

import android.app.Activity
import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView

/** 设置页里打开的许可全文或隐私说明。只接受固定资源名。 */
class LegalNoticeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val asset = intent?.getStringExtra(EXTRA_ASSET)
        val title = intent?.getStringExtra(EXTRA_TITLE) ?: ""
        if (asset == null || asset !in ALLOWED) {
            finish()
            return
        }
        setTitle(title)
        val text = TextView(this).apply {
            setPadding(48, 48, 48, 48)
            setTextIsSelectable(true)
            textSize = 14f
            text = assets.open(asset).bufferedReader().use { it.readText() }
        }
        setContentView(ScrollView(this).apply { addView(text) })
    }

    companion object {
        const val EXTRA_ASSET = "asset"
        const val EXTRA_TITLE = "title"
        private val ALLOWED = setOf(
            "legal/privacy.txt",
            "legal/lgpl-3.0.txt",
            "legal/gpl-3.0.txt",
        )
    }
}
