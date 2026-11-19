package com.yuezhou.reader

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** 阅读排版设置（方案 §9：阅读选项走 DataStore 持久化）。 */
data class ReaderPreferences(
    /** 正文字号（px）。 */
    val fontSize: Int = 18,
    /** 行距（倍数）。 */
    val lineHeight: Double = 1.6,
    /** 页面边距（px）。 */
    val pageMargin: Int = 20,
    /** 主题：light / sepia / dark。 */
    val theme: String = "light",
    /** 翻页模式：false=左右翻页，true=上下滚动。 */
    val vertical: Boolean = false,
)

private val Context.readerDataStore by preferencesDataStore(name = "reader_settings")

object ReaderSettings {

    private val KEY_FONT_SIZE = intPreferencesKey("font_size")
    private val KEY_LINE_HEIGHT = doublePreferencesKey("line_height")
    private val KEY_PAGE_MARGIN = intPreferencesKey("page_margin")
    private val KEY_THEME = stringPreferencesKey("theme")
    private val KEY_VERTICAL = booleanPreferencesKey("vertical")

    /** 读取阅读设置（Flow，随变更更新）。 */
    fun flow(context: Context): Flow<ReaderPreferences> =
        context.readerDataStore.data.map { prefs ->
            ReaderPreferences(
                fontSize = prefs[KEY_FONT_SIZE] ?: 18,
                lineHeight = prefs[KEY_LINE_HEIGHT] ?: 1.6,
                pageMargin = prefs[KEY_PAGE_MARGIN] ?: 20,
                theme = prefs[KEY_THEME] ?: "light",
                vertical = prefs[KEY_VERTICAL] ?: false,
            )
        }

    /** 保存整份设置。 */
    suspend fun save(context: Context, prefs: ReaderPreferences) {
        context.readerDataStore.edit { it ->
            it[KEY_FONT_SIZE] = prefs.fontSize
            it[KEY_LINE_HEIGHT] = prefs.lineHeight
            it[KEY_PAGE_MARGIN] = prefs.pageMargin
            it[KEY_THEME] = prefs.theme
            it[KEY_VERTICAL] = prefs.vertical
        }
    }
}
