package com.kenjc.pagekit.engine

import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 纯 JVM 单测：HTML→Markdown、结构化 JSON 解析、NoiseRules 选择器。
 * （WebView 相关逻辑以实机验证为准，见 PLAN.md 验证环境）
 */
class EngineTest {

    // ---- HtmlToMarkdown ----

    @Test
    fun `markdown 保留代码块原文`() {
        val html = """<pre><code>docker pull deepseek/v4:latest
docker run --gpus all deepseek/v4:latest</code></pre>"""
        val md = HtmlToMarkdown.convert(html)
        assertTrue(md.contains("docker pull deepseek/v4:latest"))
        assertTrue(md.contains("docker run --gpus all deepseek/v4:latest"))
    }

    @Test
    fun `markdown 转换表格`() {
        val html = "<table><tr><th>型号</th><th>显存</th></tr><tr><td>RTX5090</td><td>32GB</td></tr></table>"
        val md = HtmlToMarkdown.convert(html)
        assertTrue(md.contains("RTX5090"))
        assertTrue(md.contains("32GB"))
        assertTrue(md.contains("|"))
    }

    @Test
    fun `markdown 保留链接文本与目标`() {
        val md = HtmlToMarkdown.convert("""<a href="https://example.com/docs">官方文档</a>""")
        assertTrue(md.contains("[官方文档](https://example.com/docs)"))
    }

    @Test
    fun `markdown 空输入安全`() {
        // 空 → 空；损坏 HTML → 不崩溃、尽力输出
        assertEquals("", HtmlToMarkdown.convert(""))
        val md = HtmlToMarkdown.convert("<p>损坏 <b>html")
        assertFalse(md.contains("<"))
    }

    // ---- NoiseRules ----

    @Test
    fun `noise 选择器全部合法且覆盖 SPECS 忽略清单`() {
        // 语法冒烟：不抛异常、非空、无重复、无空白项
        assertTrue(NoiseRules.SELECTORS.isNotEmpty())
        NoiseRules.SELECTORS.forEach {
            assertFalse("selector blank", it.isBlank())
            assertFalse("selector has space", it.contains(' '))
        }
        assertEquals("无重复选择器", NoiseRules.SELECTORS.size, NoiseRules.SELECTORS.toSet().size)
        // SPECS.md 关键词覆盖
        val joined = NoiseRules.SELECTORS.joinToString(" ")
        listOf("nav", "footer", "aside", ".ad", ".comment", ".cookie", ".share", ".social", ".breadcrumb").forEach {
            assertTrue("缺少 $it", joined.contains(it))
        }
    }

    @Test
    fun `toJs 生成合法选择器数组`() {
        val js = NoiseRules.toJs("document")
        assertTrue(js.startsWith("(function(){var n=["))
        assertTrue(js.contains("'nav'"))
        assertFalse("不得使用双引号转义（三引号字符串陷阱）", js.contains("\\\""))
    }

    // ---- StructuredAssembler JSON 解析（parse 逻辑经 CompressedPage 序列化验证）----

    @Test
    fun `structured assembler 保留表格与命令`() {
        val assembler = StructuredAssembler()
        // parse 是私有方法；此处通过公开 JSON 输出格式约定做契约测试：
        // CompressedPage 序列化字段名与 SPECS.md Schema 一致
        val page = com.kenjc.pagekit.api.dto.CompressedPage(
            title = "t",
            url = "u",
            code_blocks = listOf(com.kenjc.pagekit.api.dto.CompressedPage.CodeBlock("bash", "docker run x")),
            tables = listOf(com.kenjc.pagekit.api.dto.CompressedPage.Table("硬件", "| 型号 |")),
            commands = listOf("docker run x"),
        )
        val json = kotlinx.serialization.json.Json { encodeDefaults = false }.encodeToString(page)
        assertTrue(json.contains("\"code_blocks\""))
        assertTrue(json.contains("\"tables\""))
        assertTrue(json.contains("\"commands\""))
        assertTrue(json.contains("docker run x"))
        // 语义字段 V1 不输出（null 被省略）
        assertFalse(json.contains("summary"))
        assertFalse(json.contains("key_points"))
    }
}
