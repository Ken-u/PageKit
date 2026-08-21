package com.kenjc.pagekit.provider

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KimiWebSearchAdapterTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `Kimi request 映射到标准 provider 并返回完整契约`() = runBlocking {
        var captured: WebSearchRequest? = null
        val adapter = KimiWebSearchAdapter(
            provider = WebSearchProvider { request ->
                captured = request
                WebSearchResponse(
                    query = request.query,
                    results = listOf(
                        WebSearchResult(
                            siteName = "example.com",
                            title = "PageKit",
                            url = "https://example.com/pagekit",
                            snippet = "Android WebView search",
                            content = "# PageKit",
                        ),
                    ),
                )
            },
        )

        val body = adapter.handle(
            """{"text_query":"PageKit","limit":3,"enable_page_crawling":true,"timeout_seconds":30}""",
        )
        val response = json.decodeFromString<KimiSearchResponse>(body)

        assertEquals("PageKit", captured?.query)
        assertEquals(3, captured?.limit)
        assertTrue(captured?.includeContent == true)
        assertEquals("bing", captured?.engine)
        assertEquals("example.com", response.searchResults.single().siteName)
        assertEquals("# PageKit", response.searchResults.single().content)
        assertTrue(body.contains("\"search_results\""))
        assertTrue(body.contains("\"mime\":\"\""))
    }

    @Test
    fun `Kimi 缺省参数与未知字段保持前向兼容`() = runBlocking {
        val adapter = KimiWebSearchAdapter(
            WebSearchProvider { request ->
                assertEquals(5, request.limit)
                assertFalse(request.includeContent)
                WebSearchResponse(request.query, emptyList())
            },
        )

        val response = adapter.handle("""{"text_query":"adapter","future_field":true}""")

        assertEquals(emptyList<KimiSearchResult>(), json.decodeFromString<KimiSearchResponse>(response).searchResults)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `拒绝超出 Kimi SearchWeb 上限的 limit`() {
        runBlocking {
            KimiWebSearchAdapter(WebSearchProvider { WebSearchResponse(it.query, emptyList()) })
                .handle("""{"text_query":"x","limit":21}""")
        }
    }
}
