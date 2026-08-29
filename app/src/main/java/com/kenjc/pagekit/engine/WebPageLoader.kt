package com.kenjc.pagekit.engine

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.kenjc.pagekit.api.dto.PendingDownload
import com.kenjc.pagekit.download.pendingDownloadOf
import com.kenjc.pagekit.engine.adblock.AdBlocker
import com.kenjc.pagekit.engine.adblock.NoopAdBlocker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayInputStream
import java.util.ArrayDeque

/** 页面加载状态 */
sealed interface LoadState {
    data object Idle : LoadState
    data class Loading(val url: String) : LoadState
    data class Ready(val url: String, val title: String, val elapsedMs: Long) : LoadState
    data class Failed(val url: String, val message: String) : LoadState
}

/**
 * 单个浏览器 Session 的 WebView 加载与提取控制（PLAN.md：M2）。
 *
 * - WebView 由本类在主线程创建并配置；default Session 的实例由「网页」Tab 展示
 * - 空闲判定：progress==100 + document.readyState=="complete" + 静默 800ms
 * - 超时：20s；主资源加载错误即 Failed
 * - 请求级广告拦截挂点：shouldInterceptRequest → [adBlocker]
 */
class WebPageLoader(
    context: Context,
    private val adBlocker: AdBlocker = NoopAdBlocker,
) {
    companion object {
        private const val QUIET_AFTER_COMPLETE_MS = 800L
        private const val LOAD_TIMEOUT_MS = 60_000L
        private const val READINESS_DEBOUNCE_MS = 200L
        private const val READINESS_MAX_RETRIES = 5  // 5×200ms=1s，超过后 onPageFinished 已触发即视为就绪
        private const val DEFAULT_VIEWPORT_WIDTH = 1080
        private const val DEFAULT_VIEWPORT_HEIGHT = 1920
        private const val MAX_PENDING_DOWNLOADS = 8
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var loadStartAt = 0L
    private var lastProgress = 0
    private var readinessRetryCount = 0
    private var readinessRunnable: Runnable? = null
    private var timeoutRunnable: Runnable? = null

    val state = MutableStateFlow<LoadState>(LoadState.Idle)
    val canGoBack = MutableStateFlow(false)

    /**
     * 浏览器操作（click 等）触发的下载回调队列：DownloadListener 收到后入队，
     * file_download 工具不传 URL 时取最新一条执行。上限 8 条，最旧的静默丢弃。
     */
    val pendingDownloads = ArrayDeque<PendingDownload>()

    /** Session 专属 WebView，主线程创建，随 Session 生命周期销毁。 */
    val webView: WebView = createWebView(context.applicationContext)

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(appContext: Context): WebView {
        CookieManager.getInstance().setAcceptCookie(true)
        return WebView(appContext).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                useWideViewPort = true
                loadWithOverviewMode = true
                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                allowFileAccess = true
            }
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webViewClient = LoaderClient()
            setDownloadListener(DownloadListener { url, _, contentDisposition, mimeType, contentLength ->
                synchronized(pendingDownloads) {
                    pendingDownloads.addLast(pendingDownloadOf(url, contentDisposition, mimeType, contentLength))
                    while (pendingDownloads.size > MAX_PENDING_DOWNLOADS) pendingDownloads.removeFirst()
                }
            })
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    lastProgress = newProgress
                    if (newProgress >= 100) scheduleReadinessCheck()
                }
            }
            // 离屏 Session 也需要稳定 viewport；挂到 UI 后 Compose 会按实际尺寸重新布局。
            measure(
                View.MeasureSpec.makeMeasureSpec(DEFAULT_VIEWPORT_WIDTH, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(DEFAULT_VIEWPORT_HEIGHT, View.MeasureSpec.EXACTLY),
            )
            layout(0, 0, DEFAULT_VIEWPORT_WIDTH, DEFAULT_VIEWPORT_HEIGHT)
        }
    }

    private inner class LoaderClient : WebViewClient() {

        override fun shouldInterceptRequest(
            view: WebView,
            request: WebResourceRequest,
        ): WebResourceResponse? =
            if (adBlocker.shouldBlock(request)) emptyResponse() else null

        override fun shouldOverrideUrlLoading(
            view: WebView,
            request: WebResourceRequest,
        ): Boolean {
            // 拦截非 http(s) 深链（baiduboxapp://、intent:// 等），防止 App 唤起劫持导航
            val scheme = request.url.scheme ?: return false
            return !scheme.equals("http", true) && !scheme.equals("https", true) &&
                !scheme.equals("file", true) && !scheme.equals("about", true) &&
                !scheme.equals("data", true) && !scheme.equals("javascript", true)
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            loadStartAt = SystemClock.elapsedRealtime()
            lastProgress = 0
            readinessRetryCount = 0
            cancelTimers()
            scheduleTimeout()
            state.value = LoadState.Loading(url)
        }

        override fun onPageFinished(view: WebView, url: String) {
            canGoBack.value = view.canGoBack()
            scheduleReadinessCheck()
        }

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: WebResourceError,
        ) {
            if (request.isForMainFrame) {
                cancelTimers()
                state.value = LoadState.Failed(
                    url = request.url.toString(),
                    message = error.description?.toString() ?: "加载失败",
                )
            }
        }
    }

    /**
     * 就绪判定（PLAN.md：readyState=="complete" + 静默期）。
     * 不依赖 onProgressChanged（file:// 等场景 progress 不可靠）；
     * onPageFinished 后轮询 readyState，complete 后静默 800ms → Ready。
     */
    private fun scheduleReadinessCheck() {
        readinessRunnable?.let { mainHandler.removeCallbacks(it) }
        readinessRunnable = Runnable {
            readinessRunnable = null
            webView.evaluateJavascript("document.readyState") { result ->
                when {
                    result == "\"complete\"" ->
                        mainHandler.postDelayed(
                            { if (state.value is LoadState.Loading) markReady() },
                            QUIET_AFTER_COMPLETE_MS,
                        )

                    state.value is LoadState.Loading -> {
                        readinessRetryCount++
                        if (readinessRetryCount >= READINESS_MAX_RETRIES) {
                            // readyState 长时间无法 complete（沙盒页面如 raw.githubusercontent.com
                            // 的 text/plain 内容，JS evaluate 返回 null）。onPageFinished 已触发，
                            // 主内容已加载，直接标记就绪。
                            markReady()
                        } else {
                            // readyState 尚未 complete（仍在执行 JS），继续轮询
                            mainHandler.postDelayed(
                                { scheduleReadinessCheck() },
                                READINESS_DEBOUNCE_MS,
                            )
                        }
                    }
                }
            }
        }.also { mainHandler.postDelayed(it, READINESS_DEBOUNCE_MS) }
    }

    private fun scheduleTimeout() {
        timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        timeoutRunnable = Runnable {
            if (state.value is LoadState.Loading) {
                state.value = LoadState.Failed(currentUrl(), "加载超时（20s）")
            }
        }.also { mainHandler.postDelayed(it, LOAD_TIMEOUT_MS) }
    }

    private fun markReady() {
        cancelTimers()
        val s = state.value
        if (s is LoadState.Loading) {
            state.value = LoadState.Ready(
                url = s.url,
                title = webView.title ?: "",
                elapsedMs = SystemClock.elapsedRealtime() - loadStartAt,
            )
        }
        canGoBack.value = webView.canGoBack()
    }

    private fun cancelTimers() {
        readinessRunnable?.let { mainHandler.removeCallbacks(it) }
        readinessRunnable = null
        timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        timeoutRunnable = null
    }

    fun currentUrl(): String = webView.url ?: ""

    /** 加载 URL（主线程调用；自动补全 https://，放行 file:///about:） */
    fun loadUrl(rawUrl: String) {
        val t = rawUrl.trim()
        val url = when {
            t.startsWith("http", ignoreCase = true) ||
                t.startsWith("file://") ||
                t.startsWith("about:") ||
                t.startsWith("data:") -> t

            else -> "https://$t"
        }
        // 同步切换到 Loading，令 suspend API 可以无竞态地等待本次导航的终态。
        state.value = LoadState.Loading(url)
        webView.loadUrl(url)
    }

    /**
     * 导航并等待终态（Ready/Failed）。与 [loadUrl] 的区别：返回是否成功及目标 URL，
     * 供 navigate/back 类工具反馈真实结果（含重定向后的最终地址）。
     */
    suspend fun navigateAwait(rawUrl: String, timeoutMs: Long = LOAD_TIMEOUT_MS): Pair<Boolean, String> {
        mainHandler.post { loadUrl(rawUrl) }
        val terminal = withTimeoutOrNull(timeoutMs) {
            state.first { it !is LoadState.Loading }
        }
        return when (terminal) {
            is LoadState.Ready -> true to terminal.url
            is LoadState.Failed -> false to terminal.url
            else -> false to currentUrl() // 超时仍 Loading
        }
    }

    private fun emptyResponse() = WebResourceResponse(
        "text/plain",
        "utf-8",
        ByteArrayInputStream(ByteArray(0)),
    )

    fun goBack() {
        if (webView.canGoBack()) webView.goBack()
    }

    fun destroy() {
        cancelTimers()
        webView.stopLoading()
        webView.destroy()
    }
}
