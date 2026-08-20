package com.kenjc.pagekit.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kenjc.pagekit.engine.ContentExtractor
import com.kenjc.pagekit.engine.HtmlToMarkdown
import com.kenjc.pagekit.engine.LoadState
import com.kenjc.pagekit.engine.StructuredAssembler
import com.kenjc.pagekit.engine.WebPageLoader
import com.kenjc.pagekit.engine.adblock.AdBlocker
import com.kenjc.pagekit.engine.adblock.NoopAdBlocker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** 提取结果 UI 状态 */
data class ExtractUiState(
    val running: Boolean = false,
    val ok: Boolean = false,
    val mode: String = "",
    val title: String = "",
    val byline: String = "",
    val url: String = "",
    val markdown: String = "",
    val json: String = "",
    val durationMs: Long = 0,
)

class HomeViewModel(app: Application) : AndroidViewModel(app) {

    private val adBlocker: AdBlocker = NoopAdBlocker
    private val extractor = ContentExtractor(app)
    private val assembler = StructuredAssembler()
    private val jsonFmt = Json { prettyPrint = true; encodeDefaults = false }

    /** 共享 WebView 加载器：ViewModel 初始化在主线程 */
    val loader = WebPageLoader(app, adBlocker)

    val loadState: StateFlow<LoadState> = loader.state
    val canGoBack: StateFlow<Boolean> = loader.canGoBack

    private val _extractState = MutableStateFlow(ExtractUiState())
    val extractState: StateFlow<ExtractUiState> = _extractState.asStateFlow()

    init {
        // 就绪状态变化时检查自动提取（VM 侧驱动，不依赖 UI 收集）
        viewModelScope.launch {
            loader.state.collect { maybeAutoExtract() }
        }
    }

    /** 一次性监听器：提取完成后回调（adb 验证 / 导出 / MCP fetch 复用） */
    @Volatile
    var onExtracted: ((ExtractUiState) -> Unit)? = null

    fun loadUrl(url: String) = loader.loadUrl(url)

    /** 无 UI 自动提取（intent --ez extract true）：页面就绪且尚无结果时触发，一次成功后自解除 */
    fun armAutoExtract() {
        autoExtractArmed = true
        maybeAutoExtract()
    }

    private var autoExtractArmed = false

    private fun maybeAutoExtract() {
        val s = loader.state.value
        if (autoExtractArmed && s is LoadState.Ready && !_extractState.value.ok && !_extractState.value.running) {
            extract()
            autoExtractArmed = false
        }
    }

    fun goBack() = loader.goBack()

    /** 提取主内容 → 去噪 → Markdown（viewModelScope 默认主线程，满足 WebView 约束） */
    fun extract() {
        android.util.Log.i(
            "PageKit",
            "extract() called, pid=${android.os.Process.myPid()} vm=${System.identityHashCode(this)} running=${_extractState.value.running}",
        )
        if (_extractState.value.running) return
        _extractState.value = ExtractUiState(running = true)
        viewModelScope.launch {
            val startedAt = System.currentTimeMillis()
            val result = extractor.extract(loader.webView)
            val markdown = if (result.ok) {
                withContext(Dispatchers.Default) { HtmlToMarkdown.convert(result.contentHtml) }
            } else {
                ""
            }
            val newState = ExtractUiState(
                running = false,
                ok = result.ok,
                mode = result.mode,
                title = result.title,
                byline = result.byline,
                url = loader.currentUrl(),
                markdown = markdown,
                durationMs = System.currentTimeMillis() - startedAt,
            )
            _extractState.value = newState
            android.util.Log.i(
                "PageKit",
                "extract done: ok=${newState.ok} mode=${newState.mode} mdLen=${newState.markdown.length} title=${newState.title}",
            )
            onExtracted?.invoke(newState)
            // M4：结构化装配（独立步骤，失败不影响 Markdown 结果）
            if (result.ok) {
                val page = assembler.assemble(loader.webView)
                val jsonStr = withContext(Dispatchers.Default) { jsonFmt.encodeToString(page) }
                _extractState.value = newState.copy(json = jsonStr)
                // 落盘：ResultTunnelReceiver（可能运行于独立进程）与 V2 导出读取
                runCatching {
                    val f = getApplication<Application>().filesDir.resolve("last_result.txt")
                    f.writeText(newState.markdown + "\n===JSON===\n" + jsonStr)
                    android.util.Log.i("PageKit", "result saved: ${f.path} (${f.length()} bytes)")
                }.onFailure {
                    android.util.Log.e("PageKit", "result save failed", it)
                }
            }
        }
    }

    override fun onCleared() {
        loader.destroy()
        super.onCleared()
    }
}
