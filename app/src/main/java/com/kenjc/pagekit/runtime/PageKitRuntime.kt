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
import com.kenjc.pagekit.engine.BrowserController
import com.kenjc.pagekit.engine.ContentExtractor
import com.kenjc.pagekit.engine.HtmlToMarkdown
import com.kenjc.pagekit.engine.LoadState
import com.kenjc.pagekit.engine.StructuredAssembler
import com.kenjc.pagekit.engine.WebPageLoader
import com.kenjc.pagekit.engine.adblock.AdBlocker
import com.kenjc.pagekit.engine.adblock.NoopAdBlocker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** 一次完整提取的内部结果；UI 与 MCP 复用同一份页面产物。 */
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
    suspend fun extractCurrent(request: FetchRequest): RuntimePageResult
    suspend fun expand(pageId: String?, section: String): ExpandedSection
    suspend fun listInteractiveElements(): List<String>
    suspend fun click(elementId: String): String
    suspend fun type(elementId: String, text: String): String
    suspend fun scroll(dx: Int, dy: Int): String
    suspend fun annotate(on: Boolean): String
    suspend fun title(): String
    suspend fun inspect(elementId: String): String
    suspend fun armSubmitHook(): String
}

/** WebView 驱动的 Android 运行时。 */
class AndroidPageKitRuntime(
    context: Context,
    private val adBlocker: AdBlocker = NoopAdBlocker,
    private val compressor: Compressor = NoopCompressor(),
    private val pageCache: PageExpansionCache = FilePageExpansionCache(context),
) : PageKitRuntime {

    val loader = WebPageLoader(context, adBlocker)

    private val extractor = ContentExtractor(context)
    private val assembler = StructuredAssembler()
    private val controller = BrowserController()

    override suspend fun fetch(request: FetchRequest): RuntimePageResult {
        loadAndAwait(request.url)
        return extractCurrent(request)
    }

    override suspend fun extractCurrent(request: FetchRequest): RuntimePageResult {
        val startedAt = System.currentTimeMillis()
        val pageUrl = withContext(Dispatchers.Main.immediate) { loader.currentUrl() }
        val elementHidingRules = adBlocker.elementHidingRules(pageUrl)
        val extracted = withContext(Dispatchers.Main.immediate) {
            extractor.extract(loader.webView, elementHidingRules)
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
    override suspend fun click(elementId: String): String = controller.click(loader.webView, elementId)
    override suspend fun type(elementId: String, text: String): String = controller.type(loader.webView, elementId, text)
    override suspend fun scroll(dx: Int, dy: Int): String = controller.scroll(loader.webView, dx, dy)
    override suspend fun annotate(on: Boolean): String = controller.annotate(loader.webView, on)
    override suspend fun title(): String = controller.title(loader.webView)
    override suspend fun inspect(elementId: String): String = controller.inspect(loader.webView, elementId)
    override suspend fun armSubmitHook(): String = controller.armSubmitHook(loader.webView)

    fun destroy() = loader.destroy()
}
