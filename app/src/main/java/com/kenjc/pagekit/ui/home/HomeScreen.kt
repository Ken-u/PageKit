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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Lock
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    onEnterScreensaver: () -> Unit = {},
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
    val proxyConfig by vm.proxyConfig.collectAsStateWithLifecycle()
    val mcpToken by vm.mcpToken.collectAsStateWithLifecycle()
    val screensaverTimeoutMs by vm.screensaverTimeoutMs.collectAsStateWithLifecycle()

    var showProxyDialog by rememberSaveable { mutableStateOf(false) }

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
        topBar = {
            TopAppBar(
                title = { Text("PageKit") },
                actions = {
                    IconButton(onClick = onEnterScreensaver) {
                        Icon(Icons.Default.Lock, contentDescription = "立即进入屏保")
                    }
                    IconButton(onClick = { showProxyDialog = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "设置")
                    }
                },
            )
        },
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

    if (showProxyDialog) {
        ProxySettingsDialog(
            config = proxyConfig,
            mcpToken = mcpToken,
            screensaverTimeoutMs = screensaverTimeoutMs,
            onSaveScreensaverTimeout = { vm.saveScreensaverTimeout(it) },
            onResetToken = { vm.resetMcpToken() },
            onDismiss = { showProxyDialog = false },
            onSave = { enabled, host, port, bypass ->
                vm.saveProxyConfig(enabled, host, port, bypass)
                showProxyDialog = false
            },
        )
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

@Composable
private fun ProxySettingsDialog(
    config: com.kenjc.pagekit.net.ProxyConfig,
    mcpToken: String,
    screensaverTimeoutMs: Long,
    onSaveScreensaverTimeout: (Long) -> Unit,
    onResetToken: () -> Unit,
    onDismiss: () -> Unit,
    onSave: (enabled: Boolean, host: String, port: Int, bypass: String) -> Unit,
) {
    var enabled by rememberSaveable { mutableStateOf(config.enabled) }
    var host by rememberSaveable { mutableStateOf(config.host) }
    var port by rememberSaveable { mutableStateOf(if (config.port > 0) config.port.toString() else "") }
    var bypass by rememberSaveable { mutableStateOf(config.bypass) }
    var portError by remember { mutableStateOf(false) }
    var tokenCopied by remember { mutableStateOf(false) }
    var confirmResetToken by remember { mutableStateOf(false) }
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(
                onClick = {
                    val portNum = port.toIntOrNull() ?: 0
                    onSave(enabled, host.trim(), portNum, bypass.trim())
                },
                enabled = !enabled || (host.isNotBlank() && portNumValid(port, portError)),
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
        title = { Text("代理设置") },
        text = {
            Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = enabled, onCheckedChange = { enabled = it })
                    Text("启用代理（仅 WebView）")
                }
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text("代理地址") },
                    placeholder = { Text("如 127.0.0.1") },
                    singleLine = true,
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = port,
                    onValueChange = {
                        port = it.filter { c -> c.isDigit() }
                        portError = port.isNotBlank() && (port.toIntOrNull()?.let { p -> p !in 1..65535 } ?: true)
                    },
                    label = { Text("端口") },
                    placeholder = { Text("如 7890") },
                    singleLine = true,
                    isError = portError,
                    supportingText = if (portError) ({ Text("端口范围 1-65535") }) else null,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = bypass,
                    onValueChange = { bypass = it },
                    label = { Text("不走代理的地址") },
                    placeholder = { Text("如 localhost;10.0.0.0/8;*.local") },
                    supportingText = { Text("分号分隔，留空表示全部走代理") },
                    singleLine = true,
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth(),
                )
                androidx.compose.material3.HorizontalDivider()
                Text(
                    "屏保闲置进入时间",
                    style = androidx.compose.material3.MaterialTheme.typography.labelLarge,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val options = remember {
                        listOf(30_000L to "30秒", 60_000L to "1分", 120_000L to "2分", 300_000L to "5分", 0L to "关闭")
                    }
                    options.forEach { (value, label) ->
                        androidx.compose.material3.FilterChip(
                            selected = screensaverTimeoutMs == value,
                            onClick = { onSaveScreensaverTimeout(value) },
                            label = { Text(label) },
                            modifier = Modifier.padding(end = 6.dp),
                        )
                    }
                }
                Text(
                    "顶栏月亮按钮可立即进入；应用启动后也会立即进入屏保。",
                    fontSize = 12.sp,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                )
                androidx.compose.material3.HorizontalDivider()
                Text(
                    "MCP 访问 Token",
                    style = androidx.compose.material3.MaterialTheme.typography.labelLarge,
                )
                Text(
                    mcpToken,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                )
                Text(
                    "重启与应用更新均不变；重置后立即生效，旧 Token 立即失效。",
                    fontSize = 12.sp,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row {
                    TextButton(
                        onClick = {
                            clipboard.setText(androidx.compose.ui.text.AnnotatedString(mcpToken))
                            tokenCopied = true
                        },
                    ) { Text(if (tokenCopied) "已复制" else "复制") }
                    TextButton(onClick = { confirmResetToken = true }) { Text("重置") }
                }
            }
        },
    )

    if (confirmResetToken) {
        AlertDialog(
            onDismissRequest = { confirmResetToken = false },
            title = { Text("重置 MCP Token？") },
            text = { Text("将生成新 Token 并立即生效，正在使用旧 Token 的客户端会全部失效，需要重新配置。") },
            confirmButton = {
                TextButton(onClick = {
                    onResetToken()
                    confirmResetToken = false
                }) { Text("重置") }
            },
            dismissButton = {
                TextButton(onClick = { confirmResetToken = false }) { Text("取消") }
            },
        )
    }
}

private fun portNumValid(port: String, hasError: Boolean): Boolean =
    !hasError && port.toIntOrNull()?.let { it in 1..65535 } ?: false
