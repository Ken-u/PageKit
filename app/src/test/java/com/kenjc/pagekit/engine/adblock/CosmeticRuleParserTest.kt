package com.kenjc.pagekit.engine.adblock

import java.io.File
import java.util.zip.GZIPInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CosmeticRuleParserTest {

    private val rules = CosmeticRuleParser.parse(
        listOf(
            """
                ! comment
                ##.generic-ad
                example.com##.site-ad
                example.com,example.net##.shared-ad
                ~news.example.com##.global-except-news
                example.com#@#.generic-ad
                example.com##div:has(.sponsored)
                example.com##+js(abort-current-inline-script.js)
            """.trimIndent(),
        ),
    )

    @Test
    fun `通用 域名 排除和例外规则按页面解析`() {
        val example = rules.selectorsFor("www.example.com")
        assertFalse(example.contains(".generic-ad"))
        assertTrue(example.contains(".site-ad"))
        assertTrue(example.contains(".shared-ad"))
        assertTrue(example.contains(".global-except-news"))

        val news = rules.selectorsFor("news.example.com")
        assertFalse(news.contains(".global-except-news"))

        val other = rules.selectorsFor("other.test")
        assertEquals(listOf(".generic-ad", ".global-except-news"), other)
    }

    @Test
    fun `扩展选择器与 scriptlet 明确跳过`() {
        assertEquals(CosmeticParseStats(accepted = 5, skippedUnsupported = 2), rules.stats)
        assertFalse(rules.selectorsFor("example.com").any { it.contains(":has") || it.contains("+js") })
    }

    @Test
    fun `APK 固定快照可解压并解析出足量基础规则`() {
        val texts = listOf("easylist.dat", "easylistchina.dat").map { name ->
            val asset = sequenceOf(
                File("src/main/assets/adblock/$name"),
                File("app/src/main/assets/adblock/$name"),
            ).first(File::isFile)
            GZIPInputStream(asset.inputStream()).bufferedReader().use { it.readText() }
        }

        val snapshot = CosmeticRuleParser.parse(texts)

        assertTrue("accepted=${snapshot.stats.accepted}", snapshot.stats.accepted > 20_000)
        assertTrue(snapshot.selectorsFor("example.com").isNotEmpty())
    }
}
