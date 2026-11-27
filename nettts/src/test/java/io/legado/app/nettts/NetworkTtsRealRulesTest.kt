package io.legado.app.nettts

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * 真实规则集导入校验。
 *
 * 未设置 `NETTTS_RULES_JSON` 时自动跳过（不把外部文件变成构建硬依赖）；
 * 本地验证用户提供的规则集时可执行：
 * `NETTTS_RULES_JSON=/tmp/nettts_rules.json ./gradlew :nettts:testDebugUnitTest`
 */
class NetworkTtsRealRulesTest {

    @Test
    fun `real rule set imports without errors`() {
        val path = System.getenv("NETTTS_RULES_JSON")
        assumeTrue(!path.isNullOrBlank(), "未提供 NETTTS_RULES_JSON，跳过")
        val file = File(path!!)
        assumeTrue(file.exists(), "文件不存在：$path")

        val result = NetworkTtsRuleCodec.parse(file.readText())
        assertTrue(result.rules.isNotEmpty())
        assertTrue(result.errors.isEmpty(), "解析错误：${result.errors}")
        result.rules.forEach { rule ->
            assertTrue(rule.name.isNotBlank())
            assertTrue(rule.url.startsWith("http"))
            assertTrue(rule.rawJson?.isNotBlank() == true)
        }
    }

    @Test
    fun `export of real rules keeps unknown fields`() {
        val path = System.getenv("NETTTS_RULES_JSON")
        assumeTrue(!path.isNullOrBlank(), "未提供 NETTTS_RULES_JSON，跳过")
        val file = File(path!!)
        assumeTrue(file.exists(), "文件不存在：$path")

        val rules = NetworkTtsRuleCodec.parse(file.readText()).rules
        val exported = NetworkTtsRuleCodec.exportRules(rules)
        val reparsed = NetworkTtsRuleCodec.parse(exported)
        assertEquals(rules.size, reparsed.rules.size)
        assertTrue(reparsed.errors.isEmpty(), "导出后无法再解析：${reparsed.errors}")
    }
}
