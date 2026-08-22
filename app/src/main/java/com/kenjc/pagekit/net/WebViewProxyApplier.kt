package com.kenjc.pagekit.net

import android.util.Log
import androidx.webkit.ProxyConfig as WebkitProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import java.util.concurrent.Executors

/**
 * 将 [ProxyConfig] 应用到当前进程的 WebView 网络栈。
 *
 * 使用 androidx.webkit 的 [ProxyController.setProxyOverride]，仅影响 WebView，
 * 不影响全局系统网络。需要在每个持有 WebView 的进程（主进程 + Profile worker 进程）各调用一次。
 *
 * 注意：调用时 WebView 尚未创建也可以，ProxyController 是进程级全局生效的。
 */
object WebViewProxyApplier {

    private const val TAG = "PageKitProxy"

    private val executor = Executors.newSingleThreadExecutor()

    /**
     * 应用代理配置到当前进程的 WebView。
     * 如果 [config] 未启用或无效，则清除代理（恢复直连）。
     */
    fun apply(config: ProxyConfig) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            Log.w(TAG, "ProxyController not supported on this device, skipping")
            return
        }

        val controller = ProxyController.getInstance()
        if (!config.active) {
            controller.clearProxyOverride(
                executor,
                Runnable { Log.i(TAG, "proxy cleared") },
            )
            Log.i(TAG, "proxy disabled (direct connection)")
            return
        }

        val proxyUrl = "http://${config.host}:${config.port}"
        val builder = WebkitProxyConfig.Builder()
            .addProxyRule(proxyUrl)

        config.bypassList().forEach { rule ->
            builder.addBypassRule(rule)
        }

        controller.setProxyOverride(
            builder.build(),
            executor,
            Runnable { Log.i(TAG, "proxy applied: $proxyUrl, bypass=${config.bypassList()}") },
        )
        Log.i(TAG, "proxy enabled: $proxyUrl, bypass=${config.bypassList()}")
    }
}
