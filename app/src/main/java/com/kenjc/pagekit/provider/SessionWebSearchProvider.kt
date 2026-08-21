package com.kenjc.pagekit.provider

import com.kenjc.pagekit.api.dto.FetchRequest
import com.kenjc.pagekit.session.PageKitSessionGateway

/** 将统一 WebSearchProvider 绑定到一个明确的浏览器 Session。 */
class SessionWebSearchProvider(
    private val gateway: PageKitSessionGateway,
    private val sessionId: String,
    private val maxContentChars: Int = 40_000,
) : WebSearchProvider {
    init {
        require(maxContentChars > 0) { "maxContentChars must be positive" }
    }

    override suspend fun search(request: WebSearchRequest): WebSearchResponse {
        val valid = request.validated()
        val hits = gateway.search(sessionId, valid.query.trim(), valid.engine, valid.limit)
        val results = hits.map { hit ->
            val content = if (valid.includeContent) {
                runCatching {
                    gateway.fetch(sessionId, FetchRequest(hit.url, mode = "raw"))
                        .markdown
                        .take(maxContentChars)
                }.getOrDefault("")
            } else {
                ""
            }
            WebSearchResult(
                siteName = hit.siteName,
                title = hit.title,
                url = hit.url,
                snippet = hit.snippet,
                content = content,
                date = hit.date,
                icon = hit.icon,
                mime = hit.mime,
            )
        }
        return WebSearchResponse(valid.query.trim(), results)
    }
}
