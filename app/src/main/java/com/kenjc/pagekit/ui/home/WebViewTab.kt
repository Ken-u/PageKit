package com.kenjc.pagekit.ui.home

import android.webkit.WebView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/**
 * 「网页」Tab：承载 default Session 的 WebView 实例。
 * 实例生命周期由 HomeViewModel/WebPageLoader 管理，切 Tab 仅做 attach/detach。
 */
@Composable
fun WebViewTab(webView: WebView, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { webView },
    )
}
