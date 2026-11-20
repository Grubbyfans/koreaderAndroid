package com.yuezhou.reader.speech

import java.util.Locale

/**
 * TTS 音色描述，从 [android.speech.tts.TextToSpeech] 的 [android.speech.tts.Voice] 归一而来。
 *
 * 对齐技术方案 §7.1：
 *   - 只保留「支持离线」的音色，不靠引擎名/音色名推断离线能力；
 *   - 通过 isNetworkConnectionRequired 等标志判断是否需要网络。
 *
 * @param name 引擎返回的音色名（作为 TextToSpeech.setVoice 的入参）。
 * @param locale 音色所属语言/地区。
 * @param enginePackage 提供该音色的引擎包名。
 * @param isNetworkConnectionRequired 该音色是否要求网络连接。
 * @param isInstalled 音色数据是否已安装到本地。
 */
data class TtsVoice(
    val name: String,
    val locale: Locale,
    val enginePackage: String,
    val isNetworkConnectionRequired: Boolean,
    val isInstalled: Boolean,
) {
    /** 是否满足首版离线朗读要求：已安装且不强制联网。 */
    val isOfflineCapable: Boolean get() = isInstalled && !isNetworkConnectionRequired

    /** 展示名：优先音色名，退化到语言标签。 */
    val displayName: String
        get() = name.ifBlank { locale.toLanguageTag() }

    /**
     * 音色性别推断（仅用于 UI 分组展示，非严格判定）：
     * 依据音色/引擎名中的常见关键词（male/female/men/women/female 等）。
     * 无法判断时返回 null。
     */
    val gender: Gender?
        get() {
            val haystack = (name + " " + enginePackage).lowercase()
            return when {
                haystack.contains("female") || haystack.contains("women") ||
                    haystack.contains("woman") || haystack.contains("femail") ||
                    haystack.contains("xiaofei") || haystack.contains("xiaoyan") -> Gender.FEMALE
                haystack.contains("male") || haystack.contains("man") ||
                    haystack.contains("men") -> Gender.MALE
                else -> null
            }
        }

    /** 音色性别（仅用于 UI 展示）。 */
    enum class Gender { MALE, FEMALE }
}
