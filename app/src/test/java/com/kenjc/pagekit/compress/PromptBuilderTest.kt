package com.kenjc.pagekit.compress

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptBuilderTest {

    @Test
    fun `三段结构完整`() {
        val p = PromptBuilder.buildFullPrompt("https://a.com", "标题", null, "正文内容")
        assertTrue(p.contains("=== SYSTEM ==="))
        assertTrue(p.contains("=== DEVELOPER（运行模式：raw） ==="))
        assertTrue(p.contains("=== USER ==="))
        // 顺序：SYSTEM → DEVELOPER → USER
        assertTrue(p.indexOf("=== SYSTEM ===") < p.indexOf("=== DEVELOPER"))
        assertTrue(p.indexOf("=== DEVELOPER") < p.indexOf("=== USER"))
    }

    @Test
    fun `无意图时填通用压缩`() {
        val p = PromptBuilder.buildFullPrompt("https://a.com", "t", "  ", "md")
        assertTrue(p.contains("用户意图：通用压缩"))
    }

    @Test
    fun `意图注入`() {
        val p = PromptBuilder.buildFullPrompt("https://a.com", "t", "如何部署RTX5090", "md")
        assertTrue(p.contains("用户意图：如何部署RTX5090"))
    }

    @Test
    fun `system 包含核心行为规则`() {
        val s = PromptBuilder.systemPrompt()
        assertTrue(s.contains("信息压缩引擎"))
        assertTrue(s.contains("不得改写"))
        assertTrue(s.contains("不是回答用户问题"))
    }

    @Test
    fun `developer 包含 schema 字段清单`() {
        val d = PromptBuilder.developerPrompt()
        listOf("code_blocks", "key_points", "interactive_elements", "remaining_information").forEach {
            assertTrue("缺少 $it", d.contains(it))
        }
    }
}
