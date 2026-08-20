package com.kenjc.pagekit.engine

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.kenjc.pagekit.engine.adblock.AdBlocker
import com.kenjc.pagekit.engine.adblock.NoopAdBlocker
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.ByteArrayInputStream

/** 页面加载状态 */
sealed interface LoadState {
    data object Idle : LoadState
    data class Loading(val url: String) : LoadState
    data class Ready(val url: String, val title: String, val elapsedMs: Long) : LoadState
    data class Failed(val url: String, val message: String) : LoadState
}

/**
 * 共享 WebView 的加载与提取控制（PLAN.md：M2）。
 *
 * - WebView 由本类在主线程创建并配置，「网页」Tab 直接展示该实例
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
        private const val LOAD_TIMEOUT_MS = 20_000L
        private const val READINESS_DEBOUNCE_MS = 200L
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var loadStartAt = 0L
    private var lastProgress = 0
    private var readinessRunnable: Runnable? = null
    private var timeoutRunnable: Runnable? = null

    val state = MutableStateFlow<LoadState>(LoadState.Idle)
    val canGoBack = MutableStateFlow(false)

    /** 共享 WebView 实例，主线程创建，随 ViewModel 生命周期销毁 */
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
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    lastProgress = newProgress
                    if (newProgress >= 100) scheduleReadinessCheck()
                }
            }
        }
    }

    private inner class LoaderClient : WebViewClient() {

        override fun shouldInterceptRequest(
            view: WebView,
            request: WebResourceRequest,
        ): WebResourceResponse? =
            if (adBlocker.shouldBlock(request)) emptyResponse() else null

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            loadStartAt = SystemClock.elapsedRealtime()
            lastProgress = 0
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

                    state.value is LoadState.Loading ->
                        // readyState 尚未 complete（仍在执行 JS），继续轮询
                        mainHandler.postDelayed(
                            { scheduleReadinessCheck() },
                            READINESS_DEBOUNCE_MS,
                        )
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
        webView.loadUrl(url)
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
