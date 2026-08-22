package com.kenjc.pagekit.runtime

import android.content.Context
import com.kenjc.pagekit.api.dto.CompressedPage
import com.kenjc.pagekit.api.dto.FetchRequest
import com.kenjc.pagekit.api.dto.ExpandedSection
import com.kenjc.pagekit.compress.Compressor
import com.kenjc.pagekit.compress.FilePageExpansionCache
import com.kenjc.pagekit.compress.NoopCompressor
import com.kenjc.pagekit.compress.PageExpansionCache
import com.kenjc.pagekit.compress.PageContext
import com.kenjc.pagekit.net.ProxyConfig
import com.kenjc.pagekit.net.SharedPreferencesProxySettings
import com.kenjc.pagekit.engine.BrowserController
import com.kenjc.pagekit.engine.ContentExtractor
import com.kenjc.pagekit.engine.HtmlToMarkdown
import com.kenjc.pagekit.engine.LoadState
import com.kenjc.pagekit.engine.SearchHit
import com.kenjc.pagekit.engine.SearchResultExtractor
import com.kenjc.pagekit.engine.StructuredAssembler
import com.kenjc.pagekit.engine.WebPageLoader
import com.kenjc.pagekit.engine.adblock.AdBlocker
import com.kenjc.pagekit.engine.adblock.NoopAdBlocker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable

/** goBack 等纯历史导航的等待上限；导航型操作走 WebPageLoader 自身的 60s 上限。 */
private const val NAV_BACK_TIMEOUT_MS = 15_000L

/** 一次完整提取的内部结果；UI 与 MCP 复用同一份页面产物。 */
@Serializable
data class RuntimePageResult(
    val page: CompressedPage,
    val markdown: String,
    val byline: String,
    val extractionMode: String,
    val durationMs: Long,
)

/**
 * PageKit 的有状态浏览器运行时边界。
 *
 * 接口不暴露 WebView，便于 PageKitApi 做纯 JVM 契约测试；Android 实现负责所有主线程切换。
 */
interface PageKitRuntime {
    suspend fun fetch(request: FetchRequest): RuntimePageResult
    suspend fun search(url: String, engine: String, limit: Int): List<SearchHit>
    suspend fun extractCurrent(request: FetchRequest): RuntimePageResult
    suspend fun expand(pageId: String?, section: String): ExpandedSection
    suspend fun listInteractiveElements(): List<String>
    suspend fun click(elementId: String): String
    suspend fun type(elementId: String, text: String): String
    suspend fun select(elementId: String, value: String): String
    suspend fun scroll(dx: Int, dy: Int): String
    suspend fun annotate(on: Boolean): String
    suspend fun title(): String
    suspend fun inspect(elementId: String): String
    suspend fun armSubmitHook(): String
    suspend fun currentUrl(): String

    /** 导航到 URL 并等待终态；返回 (是否 Ready, 最终 URL——含重定向)。 */
    suspend fun navigate(url: String): Pair<Boolean, String>

    /** 历史后退一页并等待终态；无历史时返回 (false, 当前 URL)。 */
    suspend fun goBack(): Pair<Boolean, String>
}

/** WebView 驱动的 Android 运行时。 */
class AndroidPageKitRuntime(
    context: Context,
    private val adBlocker: AdBlocker = NoopAdBlocker,
    private val compressor: Compressor = NoopCompressor(),
    private val pageCache: PageExpansionCache = FilePageExpansionCache(context),
) : PageKitRuntime {

    private val appContext = context.applicationContext
    val loader = WebPageLoader(context, adBlocker)

    private val extractor = ContentExtractor(context)
    private val assembler = StructuredAssembler()
    private val searchResultExtractor = SearchResultExtractor()
    private val controller = BrowserController()

    /** 提取结果 LRU：重复 URL 直接命中，交互操作后整体失效（详见 ExtractionResultCache）。 */
    private val resultCache = ExtractionResultCache()

    override suspend fun fetch(request: FetchRequest): RuntimePageResult {
        resultCache.get(request)?.let { return it }
        loadAndAwait(request.url)
        return extractCurrent(request).also { resultCache.put(request, it) }
    }

    override suspend fun search(url: String, engine: String, limit: Int): List<SearchHit> {
        loadAndAwait(url)
        return searchResultExtractor.extract(loader.webView, engine, limit)
    }

    override suspend fun extractCurrent(request: FetchRequest): RuntimePageResult {
        val startedAt = System.currentTimeMillis()
        val pageUrl = withContext(Dispatchers.Main.immediate) { loader.currentUrl() }
        val elementHidingRules = adBlocker.elementHidingRules(pageUrl)
        val extracted = withContext(Dispatchers.Main.immediate) {
            extractor.extract(loader.webView, elementHidingRules)
        }

        // JS 提取失败时（沙盒页面如 raw.githubusercontent.com 的 text/plain 内容，
        // evaluateJavascript 返回 null），用原生 HTTP 请求获取原始内容作为回退。
        if (!extracted.ok) {
            val rawContent = fetchRawContent(pageUrl.ifBlank { request.url })
            if (rawContent != null) {
                val markdown = rawContent
                val title = pageUrl.substringAfterLast('/').ifBlank { pageUrl }
                val cached = withContext(Dispatchers.IO) {
                    pageCache.store(pageUrl.ifBlank { request.url }, title, markdown)
                }
                val page = withContext(Dispatchers.Default) {
                    compressor.compress(
                        request,
                        PageContext(
                            title = title,
                            markdown = markdown,
                            structured = CompressedPage(
                                page_id = cached.pageId,
                                title = title,
                                url = pageUrl.ifBlank { request.url },
                            ),
                            pageId = cached.pageId,
                            sections = cached.sections,
                        ),
                    )
                }
                return RuntimePageResult(
                    page = page,
                    markdown = markdown,
                    byline = "",
                    extractionMode = "raw",
                    durationMs = System.currentTimeMillis() - startedAt,
                )
            }
        }
        check(extracted.ok) { "页面主内容提取失败" }

        val markdown = withContext(Dispatchers.Default) {
            HtmlToMarkdown.convert(extracted.contentHtml)
        }
        val structured = assembler.assemble(loader.webView, elementHidingRules)
        val cached = withContext(Dispatchers.IO) {
            pageCache.store(structured.url.ifBlank { request.url }, extracted.title, markdown)
        }
        val page = withContext(Dispatchers.Default) {
            compressor.compress(
                request,
                PageContext(
                    title = extracted.title,
                    markdown = markdown,
                    structured = structured,
                    pageId = cached.pageId,
                    sections = cached.sections,
                ),
            )
        }
        return RuntimePageResult(
            page = page,
            markdown = markdown,
            byline = extracted.byline,
            extractionMode = extracted.mode,
            durationMs = System.currentTimeMillis() - startedAt,
        )
    }

    override suspend fun expand(pageId: String?, section: String): ExpandedSection =
        withContext(Dispatchers.IO) { pageCache.expand(pageId, section) }

    private suspend fun loadAndAwait(url: String) = withContext(Dispatchers.Main.immediate) {
        loader.loadUrl(url)
        when (val terminal = loader.state.first { it is LoadState.Ready || it is LoadState.Failed }) {
            is LoadState.Ready -> Unit
            is LoadState.Failed -> error("${terminal.message}: ${terminal.url}")
            else -> error("unexpected load state: $terminal")
        }
    }

    override suspend fun listInteractiveElements(): List<String> = controller.snapshot(loader.webView)
    override suspend fun click(elementId: String): String = controller.click(loader.webView, elementId).also { resultCache.invalidateAll() }
    override suspend fun type(elementId: String, text: String): String = controller.type(loader.webView, elementId, text).also { resultCache.invalidateAll() }
    override suspend fun select(elementId: String, value: String): String = controller.select(loader.webView, elementId, value).also { resultCache.invalidateAll() }
    override suspend fun scroll(dx: Int, dy: Int): String = controller.scroll(loader.webView, dx, dy).also { resultCache.invalidateAll() }
    override suspend fun annotate(on: Boolean): String = controller.annotate(loader.webView, on)
    override suspend fun title(): String = controller.title(loader.webView)
    override suspend fun inspect(elementId: String): String = controller.inspect(loader.webView, elementId)
    override suspend fun armSubmitHook(): String = controller.armSubmitHook(loader.webView).also { resultCache.invalidateAll() }
    override suspend fun currentUrl(): String = withContext(Dispatchers.Main.immediate) { loader.currentUrl() }

    override suspend fun navigate(url: String): Pair<Boolean, String> =
        loader.navigateAwait(url).also { resultCache.invalidateAll() }

    override suspend fun goBack(): Pair<Boolean, String> = withContext(Dispatchers.Main.immediate) {
        if (!loader.webView.canGoBack()) {
            false to loader.currentUrl()
        } else {
            // 退一页通常不会触发 loadUrl 级别的网络等待，直接退并等待终态。
            loader.webView.goBack()
            loader.state.value = LoadState.Loading(loader.currentUrl())
            true to loader.currentUrl()
        }
    }.let { initial ->
        withTimeoutOrNull(NAV_BACK_TIMEOUT_MS) {
            loader.state.first { it !is LoadState.Loading }
        }.let { terminal ->
            when (terminal) {
                is LoadState.Ready -> true to terminal.url
                is LoadState.Failed -> false to terminal.url
                else -> initial // 超时仍 Loading：回退是否成功以初始判定为准
            }
        }
    }.also { resultCache.invalidateAll() }

    fun destroy() = loader.destroy()

    /**
     * 原生 HTTP 获取原始内容（回退方案）。
     * 用于沙盒页面（如 raw.githubusercontent.com 的 text/plain），WebView 的 evaluateJavascript 无法执行。
     * 使用与 WebView 相同的代理配置。
     */
    private fun fetchRawContent(url: String): String? = try {
        val config = SharedPreferencesProxySettings(appContext).load()
        val urlObj = java.net.URL(url)
        val (host, port) = if (config.enabled) config.host to config.port else null to 0
        val connection = if (config.enabled && host != null) {
            val proxy = java.net.Proxy(java.net.Proxy.Type.HTTP, java.net.InetSocketAddress(host, port))
            urlObj.openConnection(proxy) as java.net.HttpURLConnection
        } else {
            urlObj.openConnection() as java.net.HttpURLConnection
        }
        connection.connectTimeout = 10_000
        connection.readTimeout = 15_000
        connection.requestMethod = "GET"
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) PageKit/1.0")
        try {
            if (connection.responseCode in 200..299) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                null
            }
        } finally {
            connection.disconnect()
        }
    } catch (e: Exception) {
        null
    }
}
