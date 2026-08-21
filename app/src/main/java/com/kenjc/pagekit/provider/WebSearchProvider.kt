package com.kenjc.pagekit.provider

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 与具体 Coding Agent 协议无关的网页搜索端口。
 *
 * Agent adapter 只负责 DTO 映射；搜索引擎选择、WebView 渲染、广告过滤与正文抓取均由实现负责。
 */
fun interface WebSearchProvider {
    suspend fun search(request: WebSearchRequest): WebSearchResponse
}

@Serializable
data class WebSearchRequest(
    val query: String,
    val limit: Int = 5,
    val includeContent: Boolean = false,
    val engine: String = "bing",
) {
    fun validated(): WebSearchRequest = apply {
        require(query.isNotBlank()) { "query must not be blank" }
        require(limit in 1..20) { "limit must be between 1 and 20" }
    }
}

@Serializable
data class WebSearchResponse(
    val query: String,
    val results: List<WebSearchResult>,
)

@Serializable
data class WebSearchResult(
    @SerialName("site_name")
    val siteName: String,
    val title: String,
    val url: String,
    val snippet: String = "",
    val content: String = "",
    val date: String = "",
    val icon: String = "",
    val mime: String = "",
)
