package com.yuezhou.reader

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yuezhou.reader.reader.EpubImporter
import com.yuezhou.reader.speech.SpeechPlanStore

/** 文件管理器 ACTION_VIEW 入口：复制到私有目录、登记书架，再进入阅读。 */
class OpenEpubActivity : ComponentActivity() {
    private var source by mutableStateOf<Uri?>(null)
    private var error by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        accept(intent)
        setContent {
            YuezhouTheme {
                LaunchedEffect(source) {
                    val uri = source ?: return@LaunchedEffect
                    if (uri.scheme !in setOf("content", "file")) {
                        error = "不支持此文件来源，请从文件管理器选择 EPUB"
                        return@LaunchedEffect
                    }
                    SpeechPlanStore.stop(this@OpenEpubActivity)
                    EpubImporter.import(this@OpenEpubActivity, uri).fold(
                        onSuccess = { file ->
                            startActivity(Intent(this@OpenEpubActivity, ReaderActivity::class.java)
                                .putExtra(ReaderActivity.EXTRA_BOOK_ID, file.nameWithoutExtension))
                            finish()
                        },
                        onFailure = { error = "导入失败：${it.message}" },
                    )
                }
                Column(
                    Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (error == null) {
                        CircularProgressIndicator()
                        Text("正在导入书架…", Modifier.padding(top = 16.dp))
                    } else {
                        Text(error.orEmpty())
                        TextButton(onClick = {
                            startActivity(Intent(this@OpenEpubActivity, MainActivity::class.java))
                            finish()
                        }) { Text("返回书架") }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        accept(intent)
    }

    private fun accept(intent: Intent) {
        source = intent.data?.takeIf { intent.action == Intent.ACTION_VIEW }
        error = if (source == null) "未收到可打开的 EPUB 文件" else null
    }
}
