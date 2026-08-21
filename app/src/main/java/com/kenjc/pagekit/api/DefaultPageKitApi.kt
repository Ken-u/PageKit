package com.kenjc.pagekit.api

import com.kenjc.pagekit.api.dto.CompressedPage
import com.kenjc.pagekit.api.dto.FetchRequest
import com.kenjc.pagekit.api.dto.ExpandedSection
import com.kenjc.pagekit.engine.SearchEngine
import com.kenjc.pagekit.engine.SearchHit
import com.kenjc.pagekit.runtime.PageKitRuntime
import com.kenjc.pagekit.runtime.RuntimePageResult
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * PageKitApi 的默认实现。
 *
 * 一个 WebView 就是一条有状态浏览器会话；所有调用必须串行，避免并发导航污染页面与元素编号。
 */
class DefaultPageKitApi(
    private val runtime: PageKitRuntime,
) : PageKitApi {

    private val sessionMutex = Mutex()

    override suspend fun fetch(request: FetchRequest): CompressedPage =
        fetchDetailed(request).page

    suspend fun fetchDetailed(request: FetchRequest): RuntimePageResult =
        sessionMutex.withLock { runtime.fetch(request) }

    suspend fun extractCurrent(request: FetchRequest): RuntimePageResult =
        sessionMutex.withLock { runtime.extractCurrent(request) }

    override suspend fun expand(section: String, pageId: String?): ExpandedSection =
        sessionMutex.withLock { runtime.expand(pageId, section) }

    override suspend fun listInteractiveElements(): List<String> =
        sessionMutex.withLock { runtime.listInteractiveElements() }

    override suspend fun webSearch(query: String, engine: String): CompressedPage =
        webSearchDetailed(query, engine).page

    suspend fun webSearchDetailed(
        query: String,
        engine: String = SearchEngine.DEFAULT,
        intent: String? = null,
        mode: String = "raw",
    ): RuntimePageResult {
        val resolved = requireNotNull(SearchEngine.resolve(engine)) {
            "unknown search engine: $engine"
        }
        val url = requireNotNull(SearchEngine.buildResultUrl(resolved, query)) {
            "unknown search engine: $engine"
        }
        return fetchDetailed(FetchRequest(url = url, intent = intent, mode = mode))
    }

    /** Provider 专用的结构化搜索结果；不经过正文页压缩，避免把结果页当成单篇文章。 */
    suspend fun searchResults(
        query: String,
        engine: String = SearchEngine.DEFAULT,
        limit: Int = 5,
    ): List<SearchHit> {
        require(query.isNotBlank()) { "query must not be blank" }
        require(limit in 1..20) { "limit must be between 1 and 20" }
        val resolved = requireNotNull(SearchEngine.resolve(engine)) {
            "unknown search engine: $engine"
        }
        val url = requireNotNull(SearchEngine.buildResultUrl(resolved, query)) {
            "unknown search engine: $engine"
        }
        return sessionMutex.withLock { runtime.search(url, resolved, limit) }
    }

    override suspend fun click(elementId: String): Boolean =
        sessionMutex.withLock { operationOk(runtime.click(elementId)) }

    override suspend fun type(elementId: String, text: String): Boolean =
        sessionMutex.withLock { operationOk(runtime.type(elementId, text)) }

    override suspend fun scroll(dx: Int, dy: Int): Boolean =
        sessionMutex.withLock { operationOk(runtime.scroll(dx, dy)) }

    suspend fun clickResult(elementId: String): String = sessionMutex.withLock { runtime.click(elementId) }
    suspend fun typeResult(elementId: String, text: String): String = sessionMutex.withLock { runtime.type(elementId, text) }
    suspend fun scrollResult(dx: Int, dy: Int): String = sessionMutex.withLock { runtime.scroll(dx, dy) }
    suspend fun annotate(on: Boolean): String = sessionMutex.withLock { runtime.annotate(on) }
    suspend fun title(): String = sessionMutex.withLock { runtime.title() }
    suspend fun inspect(elementId: String): String = sessionMutex.withLock { runtime.inspect(elementId) }
    suspend fun armSubmitHook(): String = sessionMutex.withLock { runtime.armSubmitHook() }
    suspend fun currentUrl(): String = sessionMutex.withLock { runtime.currentUrl() }

    private fun operationOk(result: String): Boolean = runCatching {
        Json.parseToJsonElement(result).jsonObject["ok"]?.jsonPrimitive?.content == "true"
    }.getOrDefault(false)
}
