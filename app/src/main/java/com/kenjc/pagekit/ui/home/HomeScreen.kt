package com.kenjc.pagekit.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kenjc.pagekit.engine.LoadState

/** 顶部视图 Tab，顺序与 PLAN.md 一致：网页 / Markdown / JSON */
private val VIEW_TABS = listOf("网页", "Markdown", "JSON")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    vm: HomeViewModel = viewModel(),
    modifier: Modifier = Modifier,
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var urlInput by rememberSaveable { mutableStateOf("") }

    val loadState by vm.loadState.collectAsStateWithLifecycle()
    val canGoBack by vm.canGoBack.collectAsStateWithLifecycle()
    val extractDebug by vm.extractDebug.collectAsStateWithLifecycle()

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
                    onClick = { vm.extractRawHtml() },
                    enabled = loadState is LoadState.Ready,
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
                    text = statusText(loadState),
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
                            CircularProgressIndicator(
                                modifier = Modifier.align(Alignment.Center),
                            )
                        }
                    }

                    1 -> MarkdownTab(extractDebug = extractDebug)

                    else -> Placeholder("M4：结构化 JSON 骨架")
                }
            }
        }
    }
}

@Composable
private fun statusText(state: LoadState): String = when (state) {
    is LoadState.Idle -> "输入 URL 后点「加载」"
    is LoadState.Loading -> "加载中… ${state.url}"
    is LoadState.Ready -> "已就绪 · ${state.elapsedMs}ms · ${state.title.ifBlank { state.url }}"
    is LoadState.Failed -> "加载失败：${state.message}"
}

@Composable
private fun MarkdownTab(extractDebug: ExtractDebug?, modifier: Modifier = Modifier) {
    if (extractDebug == null) {
        Placeholder("M3：提取去噪后的 Markdown（先在「网页」Tab 加载并点「提取」）")
        return
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("M2 提取调试（M3 起替换为真正内容）")
        Text("URL：${extractDebug.url}")
        Text("HTML 长度：${extractDebug.htmlLength}")
        Text("提取耗时：${extractDebug.durationMs}ms")
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
