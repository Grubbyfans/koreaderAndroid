package com.yuezhou.reader.reader.networktts

import android.content.Context
import com.yuezhou.reader.reader.db.AppDatabase
import com.yuezhou.reader.reader.db.NetworkTtsRuleEntity
import io.legado.app.data.entities.HttpTTS
import io.legado.app.nettts.NetworkTtsRuleCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 网络 TTS 规则仓库：Room 持久化 + 原版 JSON 导入/导出。
 *
 * 导入保留原始 JSON（未知字段不丢），导出以编辑后的已知字段覆盖。
 * 登录凭证不在规则表内，导出时也不会带出。
 */
class NetworkTtsRuleRepository(private val context: Context) {

    private val dao = AppDatabase.get(context).networkTtsRuleDao()

    data class ImportOutcome(
        val imported: Int,
        val skipped: Int,
        val errors: List<String>,
    )

    suspend fun list(): List<HttpTTS> = withContext(Dispatchers.IO) {
        dao.all().map { it.toHttpTTS() }
    }

    suspend fun find(id: Long): HttpTTS? = withContext(Dispatchers.IO) {
        dao.byId(id)?.toHttpTTS()
    }

    suspend fun save(rule: HttpTTS) = withContext(Dispatchers.IO) {
        dao.upsert(rule.toEntity())
    }

    suspend fun delete(id: Long) = withContext(Dispatchers.IO) {
        dao.delete(id)
    }

    /** 导入 JSON 文本（单条或数组）；重复 id 覆盖，同名不同 id 保留两份。 */
    suspend fun import(text: String): ImportOutcome = withContext(Dispatchers.IO) {
        val parsed = NetworkTtsRuleCodec.parse(text)
        val existingIds = dao.all().map { it.id }.toHashSet()
        var imported = 0
        var skipped = 0
        parsed.rules.forEach { rule ->
            // 导入的规则若与现有规则同名且内容一致，跳过，避免重复导入同一批规则
            val duplicate = existingIds.contains(rule.id) &&
                dao.byId(rule.id)?.toHttpTTS()?.equal(rule) == true
            if (duplicate) {
                skipped++
            } else {
                dao.upsert(rule.toEntity())
                imported++
            }
        }
        ImportOutcome(imported = imported, skipped = skipped, errors = parsed.errors)
    }

    suspend fun exportAll(): String = withContext(Dispatchers.IO) {
        NetworkTtsRuleCodec.exportRules(dao.all().map { it.toHttpTTS() })
    }

    suspend fun exportOne(id: Long): String? = withContext(Dispatchers.IO) {
        dao.byId(id)?.let { NetworkTtsRuleCodec.exportRuleText(it.toHttpTTS()) }
    }

    private fun NetworkTtsRuleEntity.toHttpTTS() = HttpTTS(
        id = id,
        name = name,
        url = url,
        contentType = contentType,
        pauseDuration = pauseDuration,
        concurrentRate = concurrentRate,
        loginUrl = loginUrl,
        loginUi = loginUi,
        header = header,
        jsLib = jsLib,
        enabledCookieJar = enabledCookieJar,
        loginCheckJs = loginCheckJs,
        lastUpdateTime = lastUpdateTime,
        rawJson = rawJson,
    )

    private fun HttpTTS.toEntity() = NetworkTtsRuleEntity(
        id = id,
        name = name,
        url = url,
        contentType = contentType,
        pauseDuration = pauseDuration,
        concurrentRate = concurrentRate,
        loginUrl = loginUrl,
        loginUi = loginUi,
        header = header,
        jsLib = jsLib,
        enabledCookieJar = enabledCookieJar == true,
        loginCheckJs = loginCheckJs,
        lastUpdateTime = lastUpdateTime,
        rawJson = rawJson,
    )
}
