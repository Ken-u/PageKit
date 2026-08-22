package com.kenjc.pagekit.api

import com.kenjc.pagekit.api.dto.CompressedPage
import com.kenjc.pagekit.api.dto.FetchRequest
import com.kenjc.pagekit.api.dto.ExpandedSection
import com.kenjc.pagekit.engine.SearchHit
import com.kenjc.pagekit.runtime.PageKitRuntime
import com.kenjc.pagekit.runtime.RuntimePageResult
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultPageKitApiTest {

    @Test
    fun `所有 API 调用串行访问唯一 runtime`() = runBlocking {
        val runtime = FakeRuntime()
        val api = DefaultPageKitApi(runtime)

        coroutineScope {
            listOf(
                async { api.fetch(FetchRequest("https://example.com/1")) },
                async { api.fetch(FetchRequest("https://example.com/2")) },
                async { api.listInteractiveElements() },
                async { api.scroll(dy = 100) },
            ).awaitAll()
        }

        assertEquals(1, runtime.maxConcurrent.get())
        assertEquals(4, runtime.calls.get())
    }

    @Test
    fun `webSearch 解析引擎并复用 fetch 管线`() = runBlocking {
        val runtime = FakeRuntime()
        val api = DefaultPageKitApi(runtime)

        val result = api.webSearch("PageKit 测试", "bing")

        assertEquals(runtime.lastRequest?.url, result.url)
        assertTrue(result.url.startsWith("https://www.bing.com/search?q="))
        assertTrue(result.url.contains("PageKit"))
    }

    @Test
    fun `provider search 返回结构化结果并限制数量`() = runBlocking {
        val runtime = FakeRuntime()
        val results = DefaultPageKitApi(runtime).searchResults("PageKit", "bing", 3)

        assertEquals(1, results.size)
        assertEquals("Example", results.single().title)
        assertEquals(3, runtime.lastSearchLimit)
    }

    private class FakeRuntime : PageKitRuntime {
        val calls = AtomicInteger()
        val maxConcurrent = AtomicInteger()
        private val concurrent = AtomicInteger()
        @Volatile var lastRequest: FetchRequest? = null
        @Volatile var lastSearchLimit: Int? = null

        override suspend fun fetch(request: FetchRequest): RuntimePageResult = guarded {
            lastRequest = request
            RuntimePageResult(CompressedPage(url = request.url), "", "", "test", 1)
        }

        override suspend fun search(url: String, engine: String, limit: Int): List<SearchHit> = guarded {
            lastSearchLimit = limit
            listOf(SearchHit(siteName = "example.com", title = "Example", url = "https://example.com"))
        }

        override suspend fun extractCurrent(request: FetchRequest): RuntimePageResult = fetch(request)
        override suspend fun expand(pageId: String?, section: String): ExpandedSection = guarded {
            ExpandedSection(pageId ?: "latest", section, "Heading", "# Heading")
        }
        override suspend fun listInteractiveElements(): List<String> = guarded { emptyList() }
        override suspend fun click(elementId: String): String = guarded { ok() }
        override suspend fun type(elementId: String, text: String): String = guarded { ok() }
        override suspend fun select(elementId: String, value: String): String = guarded { ok() }
        override suspend fun scroll(dx: Int, dy: Int): String = guarded { ok() }
        override suspend fun annotate(on: Boolean): String = guarded { ok() }
        override suspend fun title(): String = guarded { ok() }
        override suspend fun inspect(elementId: String): String = guarded { ok() }
        override suspend fun armSubmitHook(): String = guarded { ok() }
        override suspend fun currentUrl(): String = guarded { lastRequest?.url.orEmpty() }
        override suspend fun navigate(url: String): Pair<Boolean, String> = guarded { true to url }
        override suspend fun goBack(): Pair<Boolean, String> = guarded { false to (lastRequest?.url.orEmpty()) }

        private suspend fun <T> guarded(block: () -> T): T {
            calls.incrementAndGet()
            val now = concurrent.incrementAndGet()
            maxConcurrent.updateAndGet { previous -> maxOf(previous, now) }
            return try {
                delay(20)
                block()
            } finally {
                concurrent.decrementAndGet()
            }
        }

        private fun ok() = """{"ok":true}"""
    }
}
