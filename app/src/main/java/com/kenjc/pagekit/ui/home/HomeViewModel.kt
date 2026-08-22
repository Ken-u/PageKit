package com.kenjc.pagekit.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kenjc.pagekit.api.DefaultPageKitApi
import com.kenjc.pagekit.api.dto.FetchRequest
import com.kenjc.pagekit.compress.PromptBuilder
import com.kenjc.pagekit.compress.LlmConfig
import com.kenjc.pagekit.compress.LlmConfigStatus
import com.kenjc.pagekit.compress.LlmSettings
import com.kenjc.pagekit.engine.LoadState
import com.kenjc.pagekit.engine.SearchEngine
import com.kenjc.pagekit.PageKitApp
import com.kenjc.pagekit.net.ProxyConfig
import com.kenjc.pagekit.ui.screensaver.ScreensaverSettings
import com.kenjc.pagekit.net.ProxySettings
import com.kenjc.pagekit.net.WebViewProxyApplier
import com.kenjc.pagekit.runtime.AndroidPageKitRuntime
import com.kenjc.pagekit.runtime.RuntimePageResult
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

class HomeViewModel(
    app: Application,
    private val runtime: AndroidPageKitRuntime,
    private val api: DefaultPageKitApi,
    private val llmSettings: LlmSettings,
    private val proxySettings: ProxySettings,
) : AndroidViewModel(app) {

    private val jsonFmt = Json { prettyPrint = true; encodeDefaults = false }

    /** UI 与 API 共用 default Session 的 WebView。 */
    val loader = runtime.loader

    /** 当前活跃 session 的 WebView 与 id（懒取：gateway 晚于 ViewModel 初始化）。 */
    val activeWebView: kotlinx.coroutines.flow.StateFlow<android.webkit.WebView?>
        get() = getApplication<PageKitApp>().sessionGateway.activeWebView
    val activeSessionId: kotlinx.coroutines.flow.StateFlow<String?>
        get() = getApplication<PageKitApp>().sessionGateway.activeSessionId

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

    private val _compressionMode = MutableStateFlow("raw")
    val compressionMode: StateFlow<String> = _compressionMode.asStateFlow()

    private val _llmStatus = MutableStateFlow(llmSettings.status())
    val llmStatus: StateFlow<LlmConfigStatus> = _llmStatus.asStateFlow()
    val llmConfigMessage = MutableStateFlow("")

    private val _proxyConfig = MutableStateFlow(proxySettings.load())
    val proxyConfig: StateFlow<ProxyConfig> = _proxyConfig.asStateFlow()

    /** MCP 访问 token：启动时固定，仅手动重置时更换（见 [resetMcpToken]）。 */
    private val _mcpToken = MutableStateFlow(getApplication<PageKitApp>().mcpTokenStore.token)
    val mcpToken: StateFlow<String> = _mcpToken.asStateFlow()

    fun resetMcpToken() {
        _mcpToken.value = getApplication<PageKitApp>().mcpTokenStore.reset()
    }

    /** 屏保闲置时长（ms），0 表示关闭闲置自动进入；立即生效。 */
    private val screensaverSettings = ScreensaverSettings(getApplication())
    private val _screensaverTimeoutMs = MutableStateFlow(screensaverSettings.load())
    val screensaverTimeoutMs: StateFlow<Long> = _screensaverTimeoutMs.asStateFlow()

    fun saveScreensaverTimeout(value: Long) {
        screensaverSettings.save(value)
        _screensaverTimeoutMs.value = value
    }

    /** 每 profile 最大并发 session 数（含 default）；调整后立即收缩主进程池，worker 进程下次重建生效。 */
    private val _maxSessions = MutableStateFlow(screensaverSettings.loadMaxSessions())
    val maxSessions: StateFlow<Int> = _maxSessions.asStateFlow()

    fun saveMaxSessions(value: Int) {
        screensaverSettings.saveMaxSessions(value)
        _maxSessions.value = value
        viewModelScope.launch {
            getApplication<PageKitApp>().sessionGateway.setMaxSessions(value)
        }
    }

    fun saveProxyConfig(enabled: Boolean, host: String, port: Int, bypass: String) {
        val config = ProxyConfig(enabled = enabled, host = host, port = port, bypass = bypass)
        proxySettings.save(config)
        _proxyConfig.value = config
        // 立即应用到当前进程的 WebView
        WebViewProxyApplier.apply(config)
    }

    fun setFocusIntent(v: String) {
        _focusIntent.value = v
    }

    fun setCompressionMode(mode: String) {
        require(mode in setOf("raw", "compact", "focus"))
        _compressionMode.value = mode
    }

    fun saveLlmConfig(endpoint: String, model: String, apiKey: String) {
        runCatching {
            val previous = llmSettings.load()
            llmSettings.save(
                LlmConfig(
                    endpoint = endpoint,
                    model = model,
                    apiKey = apiKey.takeIf(String::isNotBlank) ?: previous.apiKey,
                    maxInputChars = previous.maxInputChars,
                    maxOutputTokens = previous.maxOutputTokens,
                ),
            )
        }.onSuccess {
            _llmStatus.value = llmSettings.status()
            llmConfigMessage.value = "配置已保存"
        }.onFailure {
            llmConfigMessage.value = it.message ?: "配置无效"
        }
    }

    fun snapshotElements() {
        viewModelScope.launch {
            elements.value = api.listInteractiveElements()
            android.util.Log.i("PageKit", "snapshot: ${elements.value.size} elements")
        }
    }

    fun toggleAnnotate() {
        viewModelScope.launch {
            val next = !annotateOn.value
            api.annotate(next)
            annotateOn.value = next
        }
    }

    fun clickElement(eid: String) {
        viewModelScope.launch {
            val result = api.clickResult(eid)
            android.util.Log.i("PageKit", "click($eid): $result")
        }
    }

    /** intent 驱动的诊断/浏览器控制入口，和 MCP 共用 DefaultPageKitApi 的串行通道。 */
    fun controlOp(
        op: String,
        eid: String?,
        text: String?,
        focusIntent: String?,
        dx: Int,
        dy: Int,
        navUrl: String? = null,
    ) {
        viewModelScope.launch {
            val result = when (op) {
                "snapshot" -> api.listInteractiveElements().joinToString("\n")
                "click" -> eid?.let { api.clickResult(it) } ?: errOp("missing eid")
                "type" -> if (eid != null && text != null) api.typeResult(eid, text) else errOp("missing eid/text")
                "scroll" -> api.scrollResult(dx, dy)
                "annotate" -> {
                    val next = !annotateOn.value
                    api.annotate(next).also { annotateOn.value = next }
                }
                "title" -> api.title()
                "url" -> """{"ok":true,"result":"${loader.currentUrl()}"}"""
                "navigate" -> navUrl?.let {
                    loader.loadUrl(it)
                    """{"ok":true,"result":"navigating:$it"}"""
                } ?: errOp("missing url")
                "search" -> if (text.isNullOrBlank()) {
                    errOp("missing query")
                } else {
                    searchOp(text, focusIntent?.takeIf { it.isNotBlank() })
                    """{"ok":true,"result":"search-started"}"""
                }
                "inspect" -> eid?.let { api.inspect(it) } ?: errOp("missing eid")
                "armhook" -> api.armSubmitHook()
                "extract" -> {
                    runCatching { getApplication<Application>().filesDir.resolve("last_result.txt").delete() }
                    extract()
                    """{"ok":true,"result":"extract-started"}"""
                }
                "prompt" -> PromptBuilder.buildFullPrompt(
                    url = loader.currentUrl(),
                    title = loader.webView.title ?: "",
                    intent = focusIntent?.takeIf { it.isNotBlank() },
                    markdown = _extractState.value.markdown.ifBlank { "(尚未提取)" },
                    mode = _compressionMode.value,
                )
                else -> errOp("unknown op: $op")
            }
            android.util.Log.i("PageKit", "controlOp($op): ${result.take(120)}")
            writeControlResult(result)
        }
    }

    /** 搜索结果页导航与提取是一个可等待操作，不再遗留永久 StateFlow collector。 */
    fun searchOp(query: String, engine: String?) {
        if (_extractState.value.running) return
        _extractState.value = ExtractUiState(running = true)
        viewModelScope.launch {
            runCatching {
                api.webSearchDetailed(query, engine ?: SearchEngine.DEFAULT)
            }.onSuccess { applyResult(it) }
                .onFailure { applyFailure("webSearch", it) }
        }
    }

    private fun writeControlResult(result: String) {
        runCatching {
            getApplication<Application>().filesDir.resolve("control_result.txt").writeText(result)
        }.onFailure { android.util.Log.e("PageKit", "control result save failed", it) }
    }

    private fun errOp(msg: String) = """{"ok":false,"error":"$msg"}"""

    init {
        viewModelScope.launch {
            loader.state.collect { maybeAutoExtract() }
        }
    }

    /** 兼容 adb 验证入口；回调现在发生在结构化 JSON 完成之后。 */
    @Volatile
    var onExtracted: ((ExtractUiState) -> Unit)? = null

    fun loadUrl(url: String) = loader.loadUrl(url)

    fun armAutoExtract() {
        autoExtractArmed = true
        maybeAutoExtract()
    }

    private var autoExtractArmed = false

    private fun maybeAutoExtract() {
        val state = loader.state.value
        if (autoExtractArmed && state is LoadState.Ready && !_extractState.value.ok && !_extractState.value.running) {
            autoExtractArmed = false
            extract()
        }
    }

    fun goBack() = loader.goBack()

    fun extract() {
        if (_extractState.value.running) return
        _extractState.value = ExtractUiState(running = true)
        viewModelScope.launch {
            val request = FetchRequest(
                url = loader.currentUrl(),
                intent = _focusIntent.value.takeIf(String::isNotBlank),
                mode = _compressionMode.value,
            )
            runCatching { api.extractCurrent(request) }
                .onSuccess { applyResult(it) }
                .onFailure { applyFailure("extract", it) }
        }
    }

    private suspend fun applyResult(result: RuntimePageResult) {
        val json = withContext(Dispatchers.Default) { jsonFmt.encodeToString(result.page) }
        val state = ExtractUiState(
            running = false,
            ok = true,
            mode = result.extractionMode,
            title = result.page.title,
            byline = result.byline,
            url = result.page.url,
            markdown = result.markdown,
            json = json,
            durationMs = result.durationMs,
        )
        _extractState.value = state
        runCatching {
            val file = getApplication<Application>().filesDir.resolve("last_result.txt")
            file.writeText(state.markdown + "\n===JSON===\n" + state.json)
            android.util.Log.i("PageKit", "result saved: ${file.path} (${file.length()} bytes)")
        }.onFailure { android.util.Log.e("PageKit", "result save failed", it) }
        onExtracted?.invoke(state)
    }

    private fun applyFailure(operation: String, error: Throwable) {
        android.util.Log.e("PageKit", "$operation failed", error)
        _extractState.value = ExtractUiState(running = false, ok = false)
        writeControlResult(errOp("$operation:${error.message ?: error::class.java.simpleName}"))
    }

    override fun onCleared() {
        runtime.destroy()
        super.onCleared()
    }
}
