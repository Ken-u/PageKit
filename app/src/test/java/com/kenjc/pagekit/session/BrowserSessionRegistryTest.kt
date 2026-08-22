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

    @Test
    fun `closeIdle 只回收超阈值空闲的 session 且跳过 default`() = runBlocking {
        val destroyed = mutableMapOf<String, AtomicBoolean>()
        val registry = BrowserSessionRegistry(
            profileId = "test",
            processSlot = 0,
            maxSessions = 4,
            factory = BrowserSessionFactory { id ->
                val closed = AtomicBoolean().also { destroyed[id] = it }
                BrowserSessionComponents(DefaultPageKitApi(FakeRuntime(AtomicInteger(), AtomicInteger()))) { closed.set(true) }
            },
        )
        val old = registry.create("s0_0000000000000001")
        val fresh = registry.create("s0_0000000000000002")

        // idleMs=0：全部非默认 session 视为空闲，立即回收。
        val closed = registry.closeIdle(0)
        assertEquals(listOf(old.sessionId, fresh.sessionId).sorted(), closed.sorted())
        assertTrue(destroyed.getValue(old.sessionId).get())
        assertTrue(destroyed.getValue(fresh.sessionId).get())
        assertTrue(registry.list().isEmpty())
    }

    @Test
    fun `closeIdle 正阈值保留刚创建的 session`() = runBlocking {
        val registry = BrowserSessionRegistry(
            profileId = "test",
            processSlot = 0,
            maxSessions = 4,
            factory = BrowserSessionFactory { id ->
                BrowserSessionComponents(DefaultPageKitApi(FakeRuntime(AtomicInteger(), AtomicInteger()))) {}
            },
        )
        registry.create("s0_0000000000000001")
        assertEquals(0, registry.closeIdle(60_000).size)
        assertEquals(1, registry.list().size)
    }

    @Test
    fun `配额满时自动淘汰最旧空闲 session`() = runBlocking {
        val destroyed = mutableMapOf<String, AtomicBoolean>()
        val registry = BrowserSessionRegistry(
            profileId = "test",
            processSlot = 0,
            maxSessions = 2,
            factory = BrowserSessionFactory { id ->
                val closed = AtomicBoolean().also { destroyed[id] = it }
                BrowserSessionComponents(DefaultPageKitApi(FakeRuntime(AtomicInteger(), AtomicInteger()))) { closed.set(true) }
            },
        )
        registry.create("s0_0000000000000001")
        registry.create("s0_0000000000000002")
        // 不允许淘汰 → 配额满应报错
        try {
            registry.create("s0_0000000000000003")
            error("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
        // 允许淘汰空闲（阈值 0）→ 最旧的 s1 被回收，创建成功
        val third = registry.create("s0_0000000000000003", evictIdleMs = 0)
        assertEquals("s0_0000000000000003", third.sessionId)
        assertTrue(destroyed.getValue("s0_0000000000000001").get())
        assertFalse(destroyed.getValue("s0_0000000000000002").get())
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
        override suspend fun select(elementId: String, value: String) = ok()
        override suspend fun scroll(dx: Int, dy: Int) = ok()
        override suspend fun annotate(on: Boolean) = ok()
        override suspend fun title() = ok()
        override suspend fun inspect(elementId: String) = ok()
        override suspend fun armSubmitHook() = ok()
        override suspend fun currentUrl() = url
        override suspend fun navigate(url: String): Pair<Boolean, String> = true to url
        override suspend fun goBack(): Pair<Boolean, String> = false to url

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
