package com.kenjc.pagekit.session

import com.kenjc.pagekit.api.DefaultPageKitApi
import com.kenjc.pagekit.api.dto.CompressedPage
import com.kenjc.pagekit.api.dto.ExpandedSection
import com.kenjc.pagekit.api.dto.FileDownloadResult
import com.kenjc.pagekit.api.dto.FileInfo
import com.kenjc.pagekit.api.dto.FetchRequest
import com.kenjc.pagekit.engine.SearchHit
import com.kenjc.pagekit.runtime.PageKitRuntime
import com.kenjc.pagekit.runtime.RuntimePageResult
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.launch
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
            initialMaxSessions = 3,
            factory = BrowserSessionFactory { id ->
                val runtime = FakeRuntime(concurrent, maxConcurrent).also { runtimes[id] = it }
                val closed = AtomicBoolean().also { destroyed[id] = it }
                BrowserSessionComponents(api = DefaultPageKitApi(runtime), destroy = { closed.set(true) })
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
                defaultSession = BrowserSessionComponents(api = DefaultPageKitApi(runtime), destroy = {}),
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
            initialMaxSessions = 4,
            factory = BrowserSessionFactory { id ->
                val closed = AtomicBoolean().also { destroyed[id] = it }
                BrowserSessionComponents(api = DefaultPageKitApi(FakeRuntime(AtomicInteger(), AtomicInteger())), destroy = { closed.set(true) })
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
            initialMaxSessions = 4,
            factory = BrowserSessionFactory { id ->
                BrowserSessionComponents(api = DefaultPageKitApi(FakeRuntime(AtomicInteger(), AtomicInteger())), destroy = {})
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
            initialMaxSessions = 2,
            factory = BrowserSessionFactory { id ->
                val closed = AtomicBoolean().also { destroyed[id] = it }
                BrowserSessionComponents(api = DefaultPageKitApi(FakeRuntime(AtomicInteger(), AtomicInteger())), destroy = { closed.set(true) })
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

    @Test
    fun `list 与 closeIdle 并发时不会抛异常或卡死`() = runBlocking {
        val registry = BrowserSessionRegistry(
            profileId = "test",
            processSlot = 0,
            initialMaxSessions = 8,
            factory = BrowserSessionFactory {
                BrowserSessionComponents(
                    api = DefaultPageKitApi(FakeRuntime(AtomicInteger(), AtomicInteger())),
                    destroy = {},
                )
            },
        )
        repeat(4) { registry.create("s0_" + "%016x".format(it + 1)) }

        // 旧实现在持注册表锁期间放锁再 lock 操作锁，会被 closeIdle 抢走操作锁后抛
        // IllegalStateException；两者又互相等锁 → list() 要么异常要么永久挂起。
        val churn = launch {
            repeat(300) { i ->
                runCatching { registry.closeIdle(0) }
                runCatching { registry.create("s0_" + "%016x".format(i + 100), evictIdleMs = 0) }
            }
        }
        withTimeout(15_000) { repeat(80) { registry.list() } }
        churn.cancel()
    }

    @Test
    fun `list 跳过正在执行操作的 session 而不阻塞`() = runBlocking {
        val runtimes = mutableMapOf<String, FakeRuntime>()
        val registry = BrowserSessionRegistry(
            profileId = "test",
            processSlot = 0,
            initialMaxSessions = 3,
            factory = BrowserSessionFactory { id ->
                FakeRuntime(AtomicInteger(), AtomicInteger(), fetchDelayMs = 3_000)
                    .also { runtimes[id] = it }
                    .let { BrowserSessionComponents(api = DefaultPageKitApi(it), destroy = {}) }
            },
        )
        val busyId = registry.create("s0_0000000000000001").sessionId
        val freeId = registry.create("s0_0000000000000002").sessionId
        val busy = async {
            registry.withApi(busyId) { it.fetch(FetchRequest("https://slow.example")) }
        }
        // 等 busy 协程真正拿到操作锁
        while (runtimes.getValue(busyId).started.get() == 0) delay(5)

        val startedAt = System.currentTimeMillis()
        val listed = withTimeout(2_000) { registry.list() }
        val elapsed = System.currentTimeMillis() - startedAt

        assertTrue("list() 不应等待忙 session，实测 ${elapsed}ms", elapsed < 1_500)
        assertEquals(listOf(freeId), listed.map { it.sessionId })
        busy.cancel()
    }

    private class FakeRuntime(
        private val concurrent: AtomicInteger,
        private val maxConcurrent: AtomicInteger,
        private val fetchDelayMs: Long = 40,
    ) : PageKitRuntime {
        @Volatile var url: String = ""
        val started = AtomicInteger()

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

        override suspend fun downloadFile(url: String, suggestedFileName: String) =
            throw UnsupportedOperationException("not used in this test")
        override suspend fun downloadPendingFile(): FileDownloadResult? = null
        override suspend fun listDownloadedFiles(): List<FileInfo> = emptyList()
        override suspend fun deleteDownloadedFile(fileName: String): Boolean = false

        private suspend fun <T> guarded(block: () -> T): T {
            val active = concurrent.incrementAndGet()
            maxConcurrent.updateAndGet { maxOf(it, active) }
            started.incrementAndGet()
            return try {
                delay(fetchDelayMs)
                block()
            } finally {
                concurrent.decrementAndGet()
            }
        }

        private fun ok() = """{"ok":true}"""
    }
}
