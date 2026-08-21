package com.kenjc.pagekit.api

import com.kenjc.pagekit.api.dto.CompressedPage
import com.kenjc.pagekit.api.dto.FetchRequest
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

    private class FakeRuntime : PageKitRuntime {
        val calls = AtomicInteger()
        val maxConcurrent = AtomicInteger()
        private val concurrent = AtomicInteger()
        @Volatile var lastRequest: FetchRequest? = null

        override suspend fun fetch(request: FetchRequest): RuntimePageResult = guarded {
            lastRequest = request
            RuntimePageResult(CompressedPage(url = request.url), "", "", "test", 1)
        }

        override suspend fun extractCurrent(request: FetchRequest): RuntimePageResult = fetch(request)
        override suspend fun listInteractiveElements(): List<String> = guarded { emptyList() }
        override suspend fun click(elementId: String): String = guarded { ok() }
        override suspend fun type(elementId: String, text: String): String = guarded { ok() }
        override suspend fun scroll(dx: Int, dy: Int): String = guarded { ok() }
        override suspend fun annotate(on: Boolean): String = guarded { ok() }
        override suspend fun title(): String = guarded { ok() }
        override suspend fun inspect(elementId: String): String = guarded { ok() }
        override suspend fun armSubmitHook(): String = guarded { ok() }

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
