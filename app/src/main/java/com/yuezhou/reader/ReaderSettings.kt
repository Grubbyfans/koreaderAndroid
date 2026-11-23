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
    /** 翻页动画方式：none=无动画（瞬时），slide=平滑滑动。 */
    val pageAnimation: String = "slide",
    /** 朗读语速（TextToSpeech 范围约 0.5..2.0，1.0 为正常）。 */
    val speechRate: Float = 1.0f,
    /** 朗读音色名（TextToSpeech.setVoice 入参）；空串表示用引擎默认音色。 */
    val speechVoice: String = "",
    /** 朗读引擎：system=系统 TTS，kokoro=内置 Kokoro（仅测试版可用）。 */
    val speechEngine: String = "system",
    /** Kokoro 模型 ID；空串表示用提供者的默认模型。 */
    val kokoroModel: String = "",
    /** Kokoro 中文音色 ID。 */
    val kokoroSpeaker: Int = 3,
    /** 实验性小说解析，仅 Kokoro 使用；默认关闭。 */
    val novelTextParsing: Boolean = false,
    val fontId: String = "book",
    val fontWeight: Int = 0,
    /** 是否开启自动进度同步：开启时进入书籍自动拉取云端进度覆盖本地，退出/切出时自动上传覆盖云端。 */
    val autoSync: Boolean = false,
    /** 阅读亮度（0.0..1.0）；-1f 表示未设置，跟随系统。 */
    val brightness: Float = -1f,
)

private val Context.readerDataStore by preferencesDataStore(name = "reader_settings")

object ReaderSettings {

    private val KEY_FONT_WEIGHT = intPreferencesKey("font_weight")
    private val KEY_FONT_ID = stringPreferencesKey("font_id")
    private val KEY_FONT_SIZE = intPreferencesKey("font_size")
    private val KEY_LINE_HEIGHT = doublePreferencesKey("line_height")
    private val KEY_PAGE_MARGIN = intPreferencesKey("page_margin")
    private val KEY_THEME = stringPreferencesKey("theme")
    private val KEY_VERTICAL = booleanPreferencesKey("vertical")
    private val KEY_PAGE_ANIMATION = stringPreferencesKey("page_animation")
    private val KEY_SPEECH_RATE = floatPreferencesKey("speech_rate")
    private val KEY_SPEECH_VOICE = stringPreferencesKey("speech_voice")
    private val KEY_SPEECH_ENGINE = stringPreferencesKey("speech_engine")
    private val KEY_KOKORO_MODEL = stringPreferencesKey("kokoro_model")
    private val KEY_NOVEL_TEXT_PARSING = booleanPreferencesKey("novel_text_parsing")
    private val KEY_KOKORO_SPEAKER = intPreferencesKey("kokoro_speaker")
    private val KEY_AUTO_SYNC = booleanPreferencesKey("auto_sync")
    private val KEY_BRIGHTNESS = floatPreferencesKey("brightness")

    /** 读取阅读设置（Flow，随变更更新）。 */
    fun flow(context: Context): Flow<ReaderPreferences> =
        context.readerDataStore.data.map { prefs ->
            ReaderPreferences(
                fontSize = prefs[KEY_FONT_SIZE] ?: 18,
                fontId = prefs[KEY_FONT_ID] ?: "book",
                fontWeight = prefs[KEY_FONT_WEIGHT] ?: 0,
                lineHeight = prefs[KEY_LINE_HEIGHT] ?: 1.6,
                pageMargin = prefs[KEY_PAGE_MARGIN] ?: 12,
                theme = prefs[KEY_THEME] ?: "light",
                vertical = prefs[KEY_VERTICAL] ?: false,
                pageAnimation = prefs[KEY_PAGE_ANIMATION] ?: "slide",
                speechRate = prefs[KEY_SPEECH_RATE] ?: 1.0f,
                speechVoice = prefs[KEY_SPEECH_VOICE] ?: "",
                speechEngine = prefs[KEY_SPEECH_ENGINE] ?: "system",
                kokoroModel = prefs[KEY_KOKORO_MODEL] ?: "",
                kokoroSpeaker = prefs[KEY_KOKORO_SPEAKER] ?: 3,
                novelTextParsing = prefs[KEY_NOVEL_TEXT_PARSING] ?: false,
                autoSync = prefs[KEY_AUTO_SYNC] ?: false,
                brightness = prefs[KEY_BRIGHTNESS] ?: -1f,
            )
        }

    suspend fun selectFont(context: Context, fontId: String) {
        context.readerDataStore.edit { it[KEY_FONT_ID] = fontId }
    }

    suspend fun selectFontWeight(context: Context, weight: Int) {
        context.readerDataStore.edit { it[KEY_FONT_WEIGHT] = weight }
    }

    /** 单独保存阅读亮度（0.0..1.0）。 */
    suspend fun saveBrightness(context: Context, brightness: Float) {
        context.readerDataStore.edit { it[KEY_BRIGHTNESS] = brightness }
    }

    suspend fun setNovelTextParsing(context: Context, enabled: Boolean) {
        context.readerDataStore.edit { it[KEY_NOVEL_TEXT_PARSING] = enabled }
    }

    /** 保存整份设置。 */
    suspend fun save(context: Context, prefs: ReaderPreferences) {
        context.readerDataStore.edit { it ->
            it[KEY_FONT_SIZE] = prefs.fontSize
            it[KEY_FONT_ID] = prefs.fontId
            it[KEY_FONT_WEIGHT] = prefs.fontWeight
            it[KEY_LINE_HEIGHT] = prefs.lineHeight
            it[KEY_PAGE_MARGIN] = prefs.pageMargin
            it[KEY_THEME] = prefs.theme
            it[KEY_VERTICAL] = prefs.vertical
            it[KEY_PAGE_ANIMATION] = prefs.pageAnimation
            it[KEY_SPEECH_RATE] = prefs.speechRate
            it[KEY_SPEECH_VOICE] = prefs.speechVoice
            it[KEY_SPEECH_ENGINE] = prefs.speechEngine
            it[KEY_KOKORO_MODEL] = prefs.kokoroModel
            it[KEY_KOKORO_SPEAKER] = prefs.kokoroSpeaker
            it[KEY_NOVEL_TEXT_PARSING] = prefs.novelTextParsing
            it[KEY_AUTO_SYNC] = prefs.autoSync
            it[KEY_BRIGHTNESS] = prefs.brightness
        }
    }
}
