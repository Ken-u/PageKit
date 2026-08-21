package com.kenjc.pagekit.compress

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownSectionParserTest {
    @Test
    fun `按标题切片且不误切代码围栏`() {
        val markdown = """
            intro
            # Install
            run this
            ```markdown
            # not a section
            ```
            ## Verify
            done
        """.trimIndent()

        val sections = MarkdownSectionParser.parse("Guide", markdown)

        assertEquals(listOf("s1", "s2", "s3"), sections.map { it.sectionId })
        assertEquals(listOf("Guide", "Install", "Verify"), sections.map { it.heading })
        assertTrue(sections[1].markdown.contains("# not a section"))
    }

    @Test
    fun `无标题页面保留为单章节`() {
        val sections = MarkdownSectionParser.parse("Article", "plain body")
        assertEquals(1, sections.size)
        assertEquals("Article", sections.single().heading)
        assertEquals("plain body", sections.single().markdown)
    }

    @Test
    fun `识别 html2md 生成的 setext 标题`() {
        val sections = MarkdownSectionParser.parse(
            "Guide",
            "Install\n-------\nbody\n\nVerify\n======\ndone",
        )
        assertEquals(listOf("Install", "Verify"), sections.map { it.heading })
        assertTrue(sections.first().markdown.contains("body"))
    }
}
