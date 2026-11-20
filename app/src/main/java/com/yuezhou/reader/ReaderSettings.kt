package com.yuezhou.reader

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
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
    val pageMargin: Int = 12,
    /** 主题：light / sepia / dark。 */
    val theme: String = "light",
    /** 翻页模式：false=左右翻页，true=上下滚动。 */
    val vertical: Boolean = false,
    /** 朗读语速（TextToSpeech 范围约 0.5..2.0，1.0 为正常）。 */
    val speechRate: Float = 1.0f,
    /** 朗读音色名（TextToSpeech.setVoice 入参）；空串表示用引擎默认音色。 */
    val speechVoice: String = "",
    val fontId: String = "book",
)

private val Context.readerDataStore by preferencesDataStore(name = "reader_settings")

object ReaderSettings {

    private val KEY_FONT_ID = stringPreferencesKey("font_id")
    private val KEY_FONT_SIZE = intPreferencesKey("font_size")
    private val KEY_LINE_HEIGHT = doublePreferencesKey("line_height")
    private val KEY_PAGE_MARGIN = intPreferencesKey("page_margin")
    private val KEY_THEME = stringPreferencesKey("theme")
    private val KEY_VERTICAL = booleanPreferencesKey("vertical")
    private val KEY_SPEECH_RATE = floatPreferencesKey("speech_rate")
    private val KEY_SPEECH_VOICE = stringPreferencesKey("speech_voice")

    /** 读取阅读设置（Flow，随变更更新）。 */
    fun flow(context: Context): Flow<ReaderPreferences> =
        context.readerDataStore.data.map { prefs ->
            ReaderPreferences(
                fontSize = prefs[KEY_FONT_SIZE] ?: 18,
                fontId = prefs[KEY_FONT_ID] ?: "book",
                lineHeight = prefs[KEY_LINE_HEIGHT] ?: 1.6,
                pageMargin = prefs[KEY_PAGE_MARGIN] ?: 12,
                theme = prefs[KEY_THEME] ?: "light",
                vertical = prefs[KEY_VERTICAL] ?: false,
                speechRate = prefs[KEY_SPEECH_RATE] ?: 1.0f,
                speechVoice = prefs[KEY_SPEECH_VOICE] ?: "",
            )
        }

    suspend fun selectFont(context: Context, fontId: String) {
        context.readerDataStore.edit { it[KEY_FONT_ID] = fontId }
    }

    /** 保存整份设置。 */
    suspend fun save(context: Context, prefs: ReaderPreferences) {
        context.readerDataStore.edit { it ->
            it[KEY_FONT_SIZE] = prefs.fontSize
            it[KEY_LINE_HEIGHT] = prefs.lineHeight
            it[KEY_PAGE_MARGIN] = prefs.pageMargin
            it[KEY_THEME] = prefs.theme
            it[KEY_VERTICAL] = prefs.vertical
            it[KEY_SPEECH_RATE] = prefs.speechRate
            it[KEY_SPEECH_VOICE] = prefs.speechVoice
        }
    }
}
