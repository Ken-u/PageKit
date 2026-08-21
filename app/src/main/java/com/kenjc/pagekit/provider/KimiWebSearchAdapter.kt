package com.kenjc.pagekit.provider

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Kimi Code CLI 原生 SearchWeb 服务协议的 HTTP body adapter。 */
class KimiWebSearchAdapter(
    private val provider: WebSearchProvider,
    private val defaultEngine: String = "bing",
    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    },
) {
    suspend fun handle(requestBody: String, scopedProvider: WebSearchProvider = provider): String {
        val request = json.decodeFromString<KimiSearchRequest>(requestBody).validated()
        val response = scopedProvider.search(
            WebSearchRequest(
                query = request.textQuery,
                limit = request.limit,
                includeContent = request.enablePageCrawling,
                engine = defaultEngine,
            ),
        )
        return json.encodeToString(
            KimiSearchResponse(
                searchResults = response.results.map { result ->
                    KimiSearchResult(
                        siteName = result.siteName,
                        title = result.title,
                        url = result.url,
                        snippet = result.snippet,
                        content = result.content,
                        date = result.date,
                        icon = result.icon,
                        mime = result.mime,
                    )
                },
            ),
        )
    }
}

@Serializable
data class KimiSearchRequest(
    @SerialName("text_query") val textQuery: String,
    val limit: Int = 5,
    @SerialName("enable_page_crawling") val enablePageCrawling: Boolean = false,
    @SerialName("timeout_seconds") val timeoutSeconds: Int = 30,
) {
    fun validated(): KimiSearchRequest = apply {
        require(textQuery.isNotBlank()) { "text_query must not be blank" }
        require(limit in 1..20) { "limit must be between 1 and 20" }
        require(timeoutSeconds > 0) { "timeout_seconds must be positive" }
    }
}

@Serializable
data class KimiSearchResponse(
    @SerialName("search_results") val searchResults: List<KimiSearchResult>,
)

@Serializable
data class KimiSearchResult(
    @SerialName("site_name") val siteName: String,
    val title: String,
    val url: String,
    val snippet: String,
    val content: String = "",
    val date: String = "",
    val icon: String = "",
    val mime: String = "",
)
