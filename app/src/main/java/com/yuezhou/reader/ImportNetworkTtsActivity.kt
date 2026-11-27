package com.yuezhou.reader

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.yuezhou.reader.ui.settings.NetworkTtsRulesScreen

/** 浏览器分享链接入口，仅接收 TTS 导入，不自动执行外部规则。 */
class ImportNetworkTtsActivity : ComponentActivity() {
    private var importUrl by mutableStateOf("")
    private var requestId by mutableStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!receive(intent)) return
        enableEdgeToEdge()
        setContent {
            YuezhouTheme {
                key(requestId) {
                    NetworkTtsRulesScreen(onBack = { finish() }, externalImportUrl = importUrl)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        receive(intent)
    }

    private fun receive(incoming: Intent): Boolean {
        val uri = incoming.data
        val url = if (incoming.action == Intent.ACTION_VIEW && uri != null &&
            uri.scheme in setOf("legado", "yuezhoureader") && uri.host == "import" &&
            uri.path in setOf("/httpTTS", "/tts")) {
            uri.getQueryParameter("src") ?: uri.getQueryParameter("url")
        } else null
        val source = url?.let { android.net.Uri.parse(it) }
        if (source == null || source.scheme !in setOf("http", "https") || source.host.isNullOrBlank()) {
            Toast.makeText(this, "无效的网络 TTS 导入链接", Toast.LENGTH_LONG).show()
            finish()
            return false
        }
        importUrl = url
        requestId++
        return true
    }
}
