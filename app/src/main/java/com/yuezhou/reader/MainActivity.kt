package com.yuezhou.reader

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** 工程骨架入口。首版界面（书库/最近阅读/设置）在后续节点按方案 §12 实现。 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    HomeContent(
                        onOpenReader = {
                            startActivity(Intent(this, ReaderActivity::class.java))
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeContent(onOpenReader: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(text = "阅舟", style = MaterialTheme.typography.headlineMedium)
        Button(onClick = onOpenReader, modifier = Modifier.padding(top = 16.dp)) {
            Text("打开阅读器（P1 原型）")
        }
    }
}
