package com.kenjc.pagekit.runtime

import com.kenjc.pagekit.api.dto.CompressedPage
import com.kenjc.pagekit.api.dto.FetchRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * ExtractionResultCache 契约：
 * - url+mode+intent 完全一致才命中；
 * - 空结果（markdown 为空）不缓存，避免首次偶发失败后同 URL 一直秒回空；
 * - 变更类操作 invalidateAll 后全部失效。
 */
class ExtractionResultCacheTest {

    private fun request(url: String, mode: String = "article", intent: String? = null) =
        FetchRequest(url = url, mode = mode, intent = intent)

    private fun result(markdown: String) = RuntimePageResult(
        page = CompressedPage(url = "", title = "", sections = emptyList()),
        markdown = markdown,
        byline = "",
        extractionMode = "article",
        durationMs = 123,
    )

    @Test
    fun `same url mode intent hits cache`() {
        val cache = ExtractionResultCache()
        cache.put(request("https://a.com"), result("content"))
        assertNotNull(cache.get(request("https://a.com")))
    }

    @Test
    fun `different url mode or intent misses`() {
        val cache = ExtractionResultCache()
        cache.put(request("https://a.com"), result("content"))
        assertNull(cache.get(request("https://b.com")))
        assertNull(cache.get(request("https://a.com", mode = "raw")))
        assertNull(cache.get(request("https://a.com", intent = "expand")))
    }

    @Test
    fun `blank result is not cached`() {
        val cache = ExtractionResultCache()
        cache.put(request("https://a.com"), result(""))
        cache.put(request("https://b.com"), result("   "))
        assertNull(cache.get(request("https://a.com")))
        assertNull(cache.get(request("https://b.com")))
    }

    @Test
    fun `invalidateAll clears everything`() {
        val cache = ExtractionResultCache()
        cache.put(request("https://a.com"), result("content"))
        cache.invalidateAll()
        assertNull(cache.get(request("https://a.com")))
    }

    @Test
    fun `cached hit reports zero duration`() {
        val cache = ExtractionResultCache()
        cache.put(request("https://a.com"), result("content"))
        assertEquals(0L, cache.get(request("https://a.com"))?.durationMs)
    }
}
