package com.kenjc.pagekit.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kenjc.pagekit.engine.LoadState
import com.kenjc.pagekit.engine.WebPageLoader
import com.kenjc.pagekit.engine.adblock.AdBlocker
import com.kenjc.pagekit.engine.adblock.NoopAdBlocker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** M2 提取调试信息（M3 起被真正的 Markdown/结构化输出取代） */
data class ExtractDebug(
    val url: String,
    val htmlLength: Int,
    val durationMs: Long,
)

class HomeViewModel(app: Application) : AndroidViewModel(app) {

    private val adBlocker: AdBlocker = NoopAdBlocker

    /** 共享 WebView 加载器：主线程创建（ViewModel 初始化在主线程） */
    val loader = WebPageLoader(app, adBlocker)

    val loadState: StateFlow<LoadState> = loader.state
    val canGoBack: StateFlow<Boolean> = loader.canGoBack

    private val _extractDebug = MutableStateFlow<ExtractDebug?>(null)
    val extractDebug: StateFlow<ExtractDebug?> = _extractDebug.asStateFlow()

    fun loadUrl(url: String) = loader.loadUrl(url)

    fun goBack() = loader.goBack()

    /** M2：提取当前页原始 HTML，回显长度与耗时 */
    fun extractRawHtml() {
        viewModelScope.launch {
            val startedAt = System.currentTimeMillis()
            val html = loader.extractRawHtml()
            _extractDebug.value = ExtractDebug(
                url = loader.currentUrl(),
                htmlLength = html.length,
                durationMs = System.currentTimeMillis() - startedAt,
            )
        }
    }

    override fun onCleared() {
        loader.destroy()
        super.onCleared()
    }
}
