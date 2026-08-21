package com.kenjc.pagekit.compress

import com.kenjc.pagekit.api.dto.CompressedPage
import com.kenjc.pagekit.api.dto.FetchRequest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiCompatibleCompressorTest {
    private val settings = object : LlmSettings {
        override fun load() = LlmConfig("https://llm.example/v1", "test-model", "key")
        override fun save(config: LlmConfig) = Unit
    }

    @Test
    fun `raw 不调用 LLM 并附带 page id`() = runBlocking {
        var called = false
        val compressor = OpenAiCompatibleCompressor(settings) { _, _ ->
            called = true
            error("must not call")
        }

        val page = compressor.compress(FetchRequest("https://example.com", mode = "raw"), context())

        assertFalse(called)
        assertEquals("page123", page.page_id)
        assertEquals("original command", page.commands?.single())
    }

    @Test
    fun `compact 只采用语义字段并保护确定性字段`() = runBlocking {
        val compressor = OpenAiCompatibleCompressor(settings) { _, prompts ->
            assertTrue(prompts.developer.contains("Compact"))
            """```json
            {
              "title":"hallucinated",
              "url":"https://evil.example",
              "summary":"compressed summary",
              "key_points":["one","two"],
              "sections":[{"heading":"Install","summary":"short"}],
              "code_blocks":[{"language":"sh","content":"changed"}],
              "commands":["changed command"]
            }
            ```"""
        }

        val page = compressor.compress(FetchRequest("https://example.com", mode = "compact"), context())

        assertEquals("Original", page.title)
        assertEquals("https://example.com", page.url)
        assertEquals("compressed summary", page.summary)
        assertEquals("original code", page.code_blocks?.single()?.content)
        assertEquals("original command", page.commands?.single())
        assertTrue(page.remaining_information.orEmpty().contains("s1\tInstall"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `focus 必须提供意图`() {
        runBlocking {
            OpenAiCompatibleCompressor(settings) { _, _ -> "{}" }
                .compress(FetchRequest("https://example.com", mode = "focus"), context())
        }
    }

    private fun context() = PageContext(
        title = "Original",
        markdown = "# Install\nbody",
        structured = CompressedPage(
            title = "Original",
            url = "https://example.com",
            code_blocks = listOf(CompressedPage.CodeBlock("sh", "original code")),
            commands = listOf("original command"),
        ),
        pageId = "page123",
        sections = listOf(CachedSection("s1", "Install", "# Install\nbody")),
    )
}
