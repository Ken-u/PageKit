package com.kenjc.pagekit.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kenjc.pagekit.engine.BrowserController
import com.kenjc.pagekit.engine.ContentExtractor
import com.kenjc.pagekit.engine.HtmlToMarkdown
import com.kenjc.pagekit.engine.LoadState
import com.kenjc.pagekit.engine.SearchEngine
import com.kenjc.pagekit.engine.StructuredAssembler
import com.kenjc.pagekit.engine.WebPageLoader
import com.kenjc.pagekit.compress.PromptBuilder
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
    private val controller = BrowserController()
    private val jsonFmt = Json { prettyPrint = true; encodeDefaults = false }

    /** 共享 WebView 加载器：ViewModel 初始化在主线程 */
    val loader = WebPageLoader(app, adBlocker)

    val loadState: StateFlow<LoadState> = loader.state
    val canGoBack: StateFlow<Boolean> = loader.canGoBack

    private val _extractState = MutableStateFlow(ExtractUiState())
    val extractState: StateFlow<ExtractUiState> = _extractState.asStateFlow()

    /** M5 浏览器控制 */
    val elements = MutableStateFlow<List<String>>(emptyList())
    val annotateOn = MutableStateFlow(false)

    /** M6 Focus 意图（PromptBuilder 用户意图槽） */
    private val _focusIntent = MutableStateFlow("")
    val focusIntent: StateFlow<String> = _focusIntent.asStateFlow()

    fun setFocusIntent(v: String) {
        _focusIntent.value = v
    }

    fun snapshotElements() {
        viewModelScope.launch {
            elements.value = controller.snapshot(loader.webView)
            android.util.Log.i("PageKit", "snapshot: ${elements.value.size} elements")
        }
    }

    fun toggleAnnotate() {
        viewModelScope.launch {
            annotateOn.value = !annotateOn.value
            controller.annotate(loader.webView, annotateOn.value)
        }
    }

    fun clickElement(eid: String) {
        viewModelScope.launch {
            val r = controller.click(loader.webView, eid)
            android.util.Log.i("PageKit", "click($eid): $r")
        }
    }

    /**
     * 控制操作（intent 驱动，MCP 浏览器控制前身）：
     * op ∈ snapshot|click|type|scroll|annotate|title，结果写 filesDir/control_result.txt
     */
    fun controlOp(op: String, eid: String?, text: String?, focusIntent: String?, dx: Int, dy: Int, navUrl: String? = null) {
        viewModelScope.launch {
            val wv = loader.webView
            val result = when (op) {
                "snapshot" -> controller.snapshot(wv).joinToString("\n")
                "click" -> eid?.let { controller.click(wv, it) } ?: errOp("missing eid")
                "type" -> if (eid != null && text != null) controller.type(wv, eid, text) else errOp("missing eid/text")
                "scroll" -> controller.scroll(wv, dx, dy)
                "annotate" -> controller.annotate(wv, !annotateOn.value).also { annotateOn.value = !annotateOn.value }
                "title" -> controller.title(wv)
                // 当前 URL（诊断搜索跳转）
                "url" -> """{"ok":true,"result":"${loader.currentUrl()}"}"""
                // 导航：当前 WebView 直接加载（供脚本兜底链路）
                "navigate" -> navUrl?.let {
                    loader.loadUrl(it)
                    """{"ok":true,"result":"navigating:$it"}"""
                } ?: errOp("missing url")
                // 搜索：引擎结果页直航 + 自动提取（op=search --es text 词 [--es intent 引擎名]）
                "search" -> {
                    val q = text
                    if (q.isNullOrBlank()) {
                        errOp("missing query")
                    } else {
                        searchOp(q, focusIntent?.takeIf { it.isNotBlank() })
                        """{"ok":true,"result":"search-started"}"""
                    }
                }
                // 诊断：元素 outerHTML
                "inspect" -> eid?.let { controller.inspect(wv, it) } ?: errOp("missing eid")
                // 诊断：form submit hook（click 前调用，title 变 PK_FORM_SUBMIT_n 说明提交被触发）
                "armhook" -> controller.armSubmitHook(wv)
                // 触发提取（异步）：先清结果文件，便于调用方轮询新结果
                "extract" -> {
                    runCatching {
                        getApplication<Application>().filesDir.resolve("last_result.txt").delete()
                    }
                    extract()
                    """{"ok":true,"result":"extract-started"}"""
                }
                // M6：三段完整 Prompt（最近一次提取的 markdown + focusIntent 拼装，adb 导出验证）
                "prompt" -> PromptBuilder.buildFullPrompt(
                    url = loader.currentUrl(),
                    title = wv.title ?: "",
                    intent = focusIntent?.takeIf { it.isNotBlank() },
                    markdown = _extractState.value.markdown.ifBlank { "(尚未提取)" },
                )
                else -> errOp("unknown op: $op")
            }
            android.util.Log.i("PageKit", "controlOp($op): ${result.take(120)}")
            runCatching {
                getApplication<Application>().filesDir.resolve("control_result.txt").writeText(result)
            }.onFailure { android.util.Log.e("PageKit", "control result save failed", it) }
        }
    }

    /**
     * 搜索（websearch 工具前身）：引擎结果页直航 + 提取
     * op=search --es query "词" [--es engine bing]
     */
    fun searchOp(query: String, engine: String?) {
        viewModelScope.launch {
            val engineName = engine?.let { SearchEngine.resolve(it) } ?: SearchEngine.DEFAULT
            val resultUrl = SearchEngine.buildResultUrl(engineName, query)
            if (resultUrl == null) {
                writeControlResult("""{"ok":false,"error":"unknown-engine:$engineName"}""")
                return@launch
            }
            loader.loadUrl(resultUrl)
            android.util.Log.i("PageKit", "webSearch: engine=$engineName url=$resultUrl")
            // 等待页面就绪后自动提取一次
            loader.state.collect { state ->
                if (state is LoadState.Ready) {
                    extract()
                    return@collect
                }
            }
        }
    }

    private fun writeControlResult(result: String) {
        runCatching {
            getApplication<Application>().filesDir.resolve("control_result.txt").writeText(result)
        }
    }

    private fun errOp(msg: String) = """{"ok":false,"error":"$msg"}"""

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
