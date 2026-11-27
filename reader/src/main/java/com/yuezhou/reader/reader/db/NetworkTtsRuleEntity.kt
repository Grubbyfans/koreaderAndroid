package com.yuezhou.reader.reader.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 网络 TTS 规则（Legado 原版 HttpTTS 的持久化形态）。
 *
 * 字段与 Legado `HttpTTS` 一一对应；[rawJson] 保存导入时的原始 JSON，
 * 用于导出时保留未知字段（导出逻辑见 nettts 模块 `NetworkTtsRuleCodec`）。
 * 独立登录凭证不存本表：登录信息由加密凭证存储承担，不会随规则导出。
 */
@Entity(tableName = "network_tts_rule")
data class NetworkTtsRuleEntity(
    @PrimaryKey val id: Long,
    val name: String,
    val url: String,
    val contentType: String? = null,
    val pauseDuration: Int = 0,
    val concurrentRate: String? = "0",
    val loginUrl: String? = null,
    val loginUi: String? = null,
    val header: String? = null,
    val jsLib: String? = null,
    val enabledCookieJar: Boolean = false,
    val loginCheckJs: String? = null,
    val lastUpdateTime: Long = 0,
    /** 导入时的原始 JSON；静读侧新建规则为 null。 */
    val rawJson: String? = null,
)
