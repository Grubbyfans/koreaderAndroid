package com.yuezhou.reader

import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.yuezhou.reader.ui.components.SectionCard
import com.yuezhou.reader.ui.components.SettingNavigationRow
import com.yuezhou.reader.ui.components.SettingsSelectionRow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** 临时主题仅位于测试源集；不进入正式或测试版应用的主题注册表。 */
class YuezhouThemeExtensionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun alternateThemeReachesSharedComponentsInBothModes() {
        val alternate = YuezhouThemeDefinition(
            id = "test-ink-blue",
            light = lightColorScheme(primary = Color(0xFF24527A)),
            dark = darkColorScheme(primary = Color(0xFFA7C7EA)),
        )
        val registry = YuezhouThemeRegistry(listOf(YuezhouThemes.default, alternate), DefaultThemeId)
        val dark = mutableStateOf(false)
        var primary: Color? = null
        var cover: Color? = null
        compose.setContent {
            YuezhouThemeStyle(registry.resolve(alternate.id), dark.value, Shapes(), Typography()) {
                primary = YuezhouColors.Brand
                cover = LocalYuezhouSemanticColors.current.coverFor("same-book")
                SectionCard("主题验证") {
                    SettingNavigationRow("阅读偏好", "复用公共设置行") {}
                    SettingsSelectionRow("字体", "书籍默认") {}
                }
            }
        }
        compose.onNodeWithText("阅读偏好").assertIsDisplayed()
        compose.onNodeWithText("书籍默认").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(alternate.light.primary, primary)
            assertEquals(alternate.light.primaryContainer, cover)
            dark.value = true
        }
        compose.runOnIdle {
            assertEquals(alternate.dark.primary, primary)
            assertEquals(alternate.dark.primaryContainer, cover)
        }
    }
}
