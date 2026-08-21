package com.kenjc.pagekit.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kenjc.pagekit.PageKitApp
import com.kenjc.pagekit.compress.PromptBuilder
import com.kenjc.pagekit.engine.LoadState

/** 顶部视图 Tab，顺序与 PLAN.md 一致：网页 / Markdown / JSON / Prompt */
private val VIEW_TABS = listOf("网页", "Markdown", "JSON", "Prompt")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    initialUrl: String? = null,
    autoExtract: Boolean = false,
    modifier: Modifier = Modifier,
) {
    // 进程级单例 ViewModel：UI / ResultTunnelReceiver 共享同一 WebView 与提取状态
    val vm: HomeViewModel = (LocalContext.current.applicationContext as PageKitApp).homeViewModel

    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var urlInput by rememberSaveable {
        mutableStateOf(initialUrl.orEmpty())
    }

    val loadState by vm.loadState.collectAsStateWithLifecycle()
    val canGoBack by vm.canGoBack.collectAsStateWithLifecycle()
    val extractState by vm.extractState.collectAsStateWithLifecycle()
    val compressionMode by vm.compressionMode.collectAsStateWithLifecycle()
    val focusIntent by vm.focusIntent.collectAsStateWithLifecycle()

    // intent 驱动：初始 URL + 就绪后自动提取（adb/MCP 验证入口；提取在 VM 侧自动触发）
    LaunchedEffect(initialUrl, autoExtract) {
        if (!initialUrl.isNullOrBlank()) vm.loadUrl(initialUrl)
        if (autoExtract) vm.armAutoExtract()
    }

    // 网页 Tab 内按返回键优先走 WebView 后退
    if (selectedTab == 0) {
        BackHandler(enabled = canGoBack) { vm.goBack() }
    }

    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text("PageKit") }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            // URL 输入栏 + 加载 + 提取
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = urlInput,
                    onValueChange = { urlInput = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    label = { Text("URL") },
                    placeholder = { Text("https://…") },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        imeAction = ImeAction.Go,
                    ),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = { vm.loadUrl(urlInput) },
                    enabled = urlInput.isNotBlank(),
                ) {
                    Text("加载")
                }
                Spacer(modifier = Modifier.width(8.dp))
                OutlinedButton(
                    onClick = vm::extract,
                    enabled = loadState is LoadState.Ready &&
                        !extractState.running &&
                        (compressionMode != "focus" || focusIntent.isNotBlank()),
                ) {
                    Text("提取")
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                listOf("raw", "compact", "focus").forEach { mode ->
                    OutlinedButton(
                        onClick = { vm.setCompressionMode(mode) },
                        enabled = compressionMode != mode,
                        modifier = Modifier.padding(end = 6.dp),
                    ) { Text(mode) }
                }
                if (compressionMode == "focus") {
                    OutlinedTextField(
                        value = focusIntent,
                        onValueChange = vm::setFocusIntent,
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        label = { Text("Focus 意图") },
                    )
                }
            }

            // 顶部 Tab：网页 / Markdown / JSON
            TabRow(selectedTabIndex = selectedTab) {
                VIEW_TABS.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(title) },
                    )
                }
            }

            // 状态行
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(
                    text = when (val s = loadState) {
                        is LoadState.Idle -> "输入 URL 后点「加载」"
                        is LoadState.Loading -> "加载中… ${s.url}"
                        is LoadState.Ready -> "已就绪 · ${s.elapsedMs}ms · ${s.title.ifBlank { s.url }}"
                        is LoadState.Failed -> "加载失败：${s.message}"
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // 内容区
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when (selectedTab) {
                    0 -> {
                        WebViewTab(webView = vm.loader.webView)
                        if (loadState is LoadState.Loading) {
                            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                        }
                        ElementsPanel(vm)
                    }

                    1 -> MarkdownTab(extractState)

                    2 -> JsonTab(extractState)

                    else -> PromptTab(vm, extractState)
                }
            }
        }
    }
}

@Composable
private fun MarkdownTab(state: ExtractUiState, modifier: Modifier = Modifier) {
    ResultPane(
        state = state,
        emptyHint = "先在「网页」Tab 加载并点「提取」",
        text = { it.markdown },
        modifier = modifier,
    )
}

@Composable
private fun JsonTab(state: ExtractUiState, modifier: Modifier = Modifier) {
    ResultPane(
        state = state,
        emptyHint = "提取后展示结构化 JSON；compact/focus 会填充语义字段",
        text = { it.json.ifBlank { "（结构化装配未完成）" } },
        modifier = modifier,
    )
}

/** M6：完整 Prompt（复制贴给任意 LLM 验证输出） */
@Composable
private fun PromptTab(vm: HomeViewModel, state: ExtractUiState, modifier: Modifier = Modifier) {
    val intent by vm.focusIntent.collectAsStateWithLifecycle()
    val mode by vm.compressionMode.collectAsStateWithLifecycle()
    val llmStatus by vm.llmStatus.collectAsStateWithLifecycle()
    val llmConfigMessage by vm.llmConfigMessage.collectAsStateWithLifecycle()
    var endpoint by rememberSaveable { mutableStateOf(llmStatus.endpoint) }
    var model by rememberSaveable { mutableStateOf(llmStatus.model) }
    var apiKey by remember { mutableStateOf("") }
    val context = androidx.compose.ui.platform.LocalContext.current
    val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
    val prompt = if (state.ok) {
        PromptBuilder.buildFullPrompt(
            url = state.url,
            title = state.title,
            intent = intent,
            markdown = state.markdown,
            mode = mode,
        )
    } else {
        ""
    }

    fun copy() {
        clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("PageKit Prompt", prompt))
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = intent,
                onValueChange = vm::setFocusIntent,
                modifier = Modifier.weight(1f),
                singleLine = true,
                label = { Text("用户意图（Focus 模式，可空）") },
            )
            Spacer(modifier = Modifier.width(8.dp))
            TextButton(onClick = ::copy, enabled = state.ok) { Text("复制完整 Prompt") }
        }
        Text("OpenAI-compatible Compressor", modifier = Modifier.padding(top = 16.dp))
        OutlinedTextField(
            value = endpoint,
            onValueChange = { endpoint = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Endpoint（base URL）") },
        )
        OutlinedTextField(
            value = model,
            onValueChange = { model = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Model") },
        )
        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            label = { Text(if (llmStatus.hasApiKey) "API key（已保存；留空则保留）" else "API key（本地模型可空）") },
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = {
                vm.saveLlmConfig(endpoint, model, apiKey)
                apiKey = ""
            }) { Text("保存 LLM 配置") }
            Text(
                llmConfigMessage.ifBlank {
                    if (llmStatus.configured) "已配置" else "未配置（raw 不受影响）"
                },
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        Text(
            prompt.ifBlank { "提取后可导出 SPECS.md 三段完整 Prompt" },
            modifier = Modifier.padding(top = 8.dp),
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun ResultPane(
    state: ExtractUiState,
    emptyHint: String,
    text: (ExtractUiState) -> String,
    modifier: Modifier = Modifier,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)

    fun content() = text(state)

    fun copy() {
        clipboard?.setPrimaryClip(
            android.content.ClipData.newPlainText("PageKit", content()),
        )
    }

    fun share() {
        context.startActivity(
            android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(android.content.Intent.EXTRA_TEXT, content())
            }.let { android.content.Intent.createChooser(it, "分享") },
        )
    }

    when {
        state.running -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }

        !state.ok -> Placeholder(emptyHint)

        else -> Column(
            modifier = modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${state.title} · ${state.mode} · ${state.durationMs}ms",
                    style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = ::copy) { Text("复制") }
                TextButton(onClick = ::share) { Text("分享") }
            }
            Text(
                content(),
                modifier = Modifier.padding(top = 8.dp),
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

@Composable
private fun Placeholder(hint: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text(hint, modifier = Modifier.padding(16.dp))
    }
}

/** M5 元素标注面板：右下角可展开，快照列表 + 点击触发 + 标注开关 */
@Composable
private fun BoxScope.ElementsPanel(vm: HomeViewModel) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val elements by vm.elements.collectAsStateWithLifecycle()
    val annotateOn by vm.annotateOn.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .padding(12.dp),
        horizontalAlignment = Alignment.End,
    ) {
        if (expanded) {
            androidx.compose.material3.Surface(
                tonalElevation = 3.dp,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
            ) {
                Column(
                    modifier = Modifier
                        .padding(12.dp)
                        .heightIn(max = 260.dp)
                        .widthIn(max = 320.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Row {
                        TextButton(onClick = vm::snapshotElements) { Text("快照") }
                        TextButton(onClick = vm::toggleAnnotate) {
                            Text(if (annotateOn) "标注:开" else "标注:关")
                        }
                    }
                    elements.forEach { line ->
                        val eid = line.substringAfter('[').substringBefore(']').ifBlank { null }
                        TextButton(
                            onClick = { eid?.let(vm::clickElement) },
                            enabled = eid != null,
                        ) {
                            Text(line, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (elements.isEmpty()) {
                        Text("点「快照」枚举页面元素", style = androidx.compose.material3.MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        androidx.compose.material3.FloatingActionButton(
            onClick = { expanded = !expanded },
        ) { Text(if (expanded) "×" else "[e]") }
    }
}
