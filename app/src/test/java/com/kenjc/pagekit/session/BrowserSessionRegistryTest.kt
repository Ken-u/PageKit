package com.kenjc.pagekit.session

import com.kenjc.pagekit.api.DefaultPageKitApi
import com.kenjc.pagekit.api.dto.CompressedPage
import com.kenjc.pagekit.api.dto.ExpandedSection
import com.kenjc.pagekit.api.dto.FetchRequest
import com.kenjc.pagekit.engine.SearchHit
import com.kenjc.pagekit.runtime.PageKitRuntime
import com.kenjc.pagekit.runtime.RuntimePageResult
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserSessionRegistryTest {
    @Test
    fun `不同 session 可并行且页面状态互不覆盖`() = runBlocking {
        val runtimes = mutableMapOf<String, FakeRuntime>()
        val destroyed = mutableMapOf<String, AtomicBoolean>()
        val concurrent = AtomicInteger()
        val maxConcurrent = AtomicInteger()
        val registry = BrowserSessionRegistry(
            profileId = "test",
            processSlot = 0,
            maxSessions = 3,
            factory = BrowserSessionFactory { id ->
                val runtime = FakeRuntime(concurrent, maxConcurrent).also { runtimes[id] = it }
                val closed = AtomicBoolean().also { destroyed[id] = it }
                BrowserSessionComponents(DefaultPageKitApi(runtime)) { closed.set(true) }
            },
        )
        val first = registry.create("s0_0000000000000001")
        val second = registry.create("s0_0000000000000002")

        val a = async {
            registry.withApi(first.sessionId) { it.fetch(FetchRequest("https://a.example")) }
        }
        val b = async {
            registry.withApi(second.sessionId) { it.fetch(FetchRequest("https://b.example")) }
        }
        a.await(); b.await()

        assertEquals(2, maxConcurrent.get())
        assertEquals("https://a.example", runtimes.getValue(first.sessionId).url)
        assertEquals("https://b.example", runtimes.getValue(second.sessionId).url)
        assertTrue(registry.close(first.sessionId))
        assertTrue(destroyed.getValue(first.sessionId).get())
        assertFalse(destroyed.getValue(second.sessionId).get())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `default UI session 不允许关闭`() {
        runBlocking {
            val runtime = FakeRuntime(AtomicInteger(), AtomicInteger())
            val registry = BrowserSessionRegistry(
                profileId = DEFAULT_PROFILE_ID,
                processSlot = 0,
                defaultSession = BrowserSessionComponents(DefaultPageKitApi(runtime)) {},
                factory = BrowserSessionFactory { error("not used") },
            )
            registry.close(DEFAULT_SESSION_ID)
        }
    }

    private class FakeRuntime(
        private val concurrent: AtomicInteger,
        private val maxConcurrent: AtomicInteger,
    ) : PageKitRuntime {
        @Volatile var url: String = ""

        override suspend fun fetch(request: FetchRequest): RuntimePageResult = guarded {
            url = request.url
            RuntimePageResult(CompressedPage(url = url), "# $url", "", "test", 1)
        }

        override suspend fun search(url: String, engine: String, limit: Int): List<SearchHit> = emptyList()
        override suspend fun extractCurrent(request: FetchRequest): RuntimePageResult = fetch(request)
        override suspend fun expand(pageId: String?, section: String) = ExpandedSection("p", "s1", "h", "m")
        override suspend fun listInteractiveElements(): List<String> = emptyList()
        override suspend fun click(elementId: String) = ok()
        override suspend fun type(elementId: String, text: String) = ok()
        override suspend fun scroll(dx: Int, dy: Int) = ok()
        override suspend fun annotate(on: Boolean) = ok()
        override suspend fun title() = ok()
        override suspend fun inspect(elementId: String) = ok()
        override suspend fun armSubmitHook() = ok()
        override suspend fun currentUrl() = url

        private suspend fun <T> guarded(block: () -> T): T {
            val active = concurrent.incrementAndGet()
            maxConcurrent.updateAndGet { maxOf(it, active) }
            return try {
                delay(40)
                block()
            } finally {
                concurrent.decrementAndGet()
            }
        }

        private fun ok() = """{"ok":true}"""
    }
}
