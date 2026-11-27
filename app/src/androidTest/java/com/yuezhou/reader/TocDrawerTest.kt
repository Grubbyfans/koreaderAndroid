package com.yuezhou.reader

import com.yuezhou.reader.ui.reader.TocDrawer

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import com.yuezhou.reader.reader.TocItem
import org.junit.Rule
import org.junit.Test

class TocDrawerTest {
    @get:Rule val compose = createComposeRule()

    private val chapters = (0 until 40).map { index ->
        TocItem("卷 $index", "volume$index.xhtml", id = index,
            subitems = if (index == 18) (0 until 40).map { child ->
                TocItem("章节 $child", "volume18.xhtml#c$child", id = 100 + child)
            } else emptyList())
    }

    @Test fun openingRevealsNestedChapterBeyondCollapsedListSize() {
        val open = mutableStateOf(false)
        compose.setContent {
            MaterialTheme {
                TocDrawer(chapters, open.value, 135, null, "目录测试", 0.5f, {}, {})
            }
        }
        compose.runOnIdle { open.value = true }
        compose.onNodeWithText("章节 35").assertIsDisplayed()
        compose.onNodeWithText("在读").assertIsDisplayed()

        // 手动折叠后重新打开，必须再次展开并定位。
        compose.onNode(hasScrollAction()).performScrollToIndex(18)
        compose.onNodeWithContentDescription("收起 卷 18").performClick()
        compose.runOnIdle { open.value = false }
        compose.runOnIdle { open.value = true }
        compose.onNodeWithText("章节 35").assertIsDisplayed()
    }

    @Test fun lateTocAndHrefFallbackRevealDeepChapter() {
        val items = mutableStateOf(emptyList<TocItem>())
        val deep = TocItem("深层章节", "deep.xhtml#target")
        val tree = chapters + TocItem("父目录", "", subitems = listOf(
            TocItem("二级目录", "", subitems = listOf(deep))
        ))
        compose.setContent {
            MaterialTheme {
                TocDrawer(items.value, true, null, deep.href, "目录测试", 0.5f, {}, {})
            }
        }
        compose.runOnIdle { items.value = tree }
        compose.onNodeWithText("深层章节").assertIsDisplayed()
        compose.onNodeWithText("在读").assertIsDisplayed()
    }
}
