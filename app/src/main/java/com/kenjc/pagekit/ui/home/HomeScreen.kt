package com.kenjc.pagekit.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kenjc.pagekit.PageKitApp
import com.kenjc.pagekit.engine.LoadState

/** 顶部视图 Tab，顺序与 PLAN.md 一致：网页 / Markdown / JSON */
private val VIEW_TABS = listOf("网页", "Markdown", "JSON")

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
                    enabled = loadState is LoadState.Ready && !extractState.running,
                ) {
                    Text("提取")
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
                    }

                    1 -> MarkdownTab(extractState)

                    else -> JsonTab(extractState)
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
        emptyHint = "提取后展示 SPECS.md JSON 骨架（语义字段 V2 由 LLM 填充）",
        text = { it.json.ifBlank { "（结构化装配未完成）" } },
        modifier = modifier,
    )
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
