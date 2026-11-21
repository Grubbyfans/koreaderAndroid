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
     * 依据音色/引擎名中的常见关键词（male/female、男/女、常见国产引擎命名等）。
     * 无法判断时返回 null。
     */
    val gender: Gender?
        get() {
            val haystack = (name + " " + enginePackage).lowercase()
            // 女声关键词
            val femaleHints = listOf(
                "female", "women", "woman", "femail",
                "xiaofei", "xiaoyan", "xiaoxiao", "xiaoyi", "xiaomeng", "xiaoxuan",
                "晓晓", "晓伊", "晓梦", "晓萱", "晓菲", "晓妍",
                "女",
            )
            // 男声关键词（放在女声之后判断，避免 "female" 命中 "male"）
            val maleHints = listOf(
                "male", "man", "men",
                "yunxi", "yunyang", "yunfeng", "yunjian", "yunhao",
                "云希", "云扬", "云风", "云健", "云皓",
                "男",
            )
            // 先判女声，因为 female 含 male 子串
            if (femaleHints.any { haystack.contains(it) }) return Gender.FEMALE
            if (maleHints.any { haystack.contains(it) }) return Gender.MALE
            // 三星 SMT 引擎命名规律：音色名形如 en-US-SMTg02 / ja-JP-SMTf00 / zh-CN-SMTl02，
            // "SMT" 后紧跟的字母表示性别：g/f = 女声，l/m = 男声。
            val samsungMatch = Regex("smt([glfmi])", RegexOption.IGNORE_CASE).find(name)
            if (samsungMatch != null) {
                return when (samsungMatch.groupValues[1].lowercase()) {
                    "g", "f" -> Gender.FEMALE
                    "l", "m" -> Gender.MALE
                    else -> null
                }
            }
            return null
        }

    /** 音色性别（仅用于 UI 展示）。 */
    enum class Gender { MALE, FEMALE }
}
