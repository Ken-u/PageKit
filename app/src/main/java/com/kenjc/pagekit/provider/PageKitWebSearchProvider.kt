package com.kenjc.pagekit.provider

import com.kenjc.pagekit.api.DefaultPageKitApi
import com.kenjc.pagekit.api.dto.FetchRequest

/** 使用 PageKit 的共享 WebView、Cookie、广告规则和正文提取管线实现搜索。 */
class PageKitWebSearchProvider(
    private val api: DefaultPageKitApi,
    private val maxContentChars: Int = 40_000,
) : WebSearchProvider {

    init {
        require(maxContentChars > 0) { "maxContentChars must be positive" }
    }

    override suspend fun search(request: WebSearchRequest): WebSearchResponse {
        val valid = request.validated()
        val hits = api.searchResults(
            query = valid.query.trim(),
            engine = valid.engine,
            limit = valid.limit,
        )
        val results = hits.map { hit ->
            val content = if (valid.includeContent) {
                runCatching {
                    api.fetchDetailed(FetchRequest(url = hit.url, mode = "raw"))
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
        return WebSearchResponse(query = valid.query.trim(), results = results)
    }
}
