# PageKit

**WebFetch 信息压缩引擎**的 Android 实现（见 [SPECS.md](SPECS.md)）——把已完整渲染的网页转换成高信息密度、低 Token 消耗、几乎无信息损失的结构化结果，供下游 LLM/Agent 继续推理。

> 保真压缩（Information Compression），而不是内容摘要（Summary）。

## 功能

| 能力 | 说明 |
| --- | --- |
| 网页渲染 | 应用内 WebView（JS/Cookie/登录态可用），顶部 Tab 真实可交互 |
| 搜索引擎直航 | 五引擎 URL 模板（Bing 默认 / Baidu / Sogou / 360 / Google），输入关键词直接拼接结果页 URL 导航，无需进首页 |
| 主内容提取 | Readability.js + 克隆 DOM 去噪（SPECS.md 忽略清单），最大文本块兜底 |
| Markdown 输出 | flexmark html2md，代码/表格/链接原文保留 |
| 结构化 JSON | SPECS.md Schema：确定性字段本地保真提取，语义字段由可配置 LLM 填充 |
| 浏览器控制 | 元素快照 `[eN]` 编号 + click/type/scroll + 元素标注开关 |
| Prompt 导出 | SPECS.md System/Developer/User 三段完整 Prompt，可复制贴给任意 LLM |
| LLM 压缩 | OpenAI-compatible / 端侧 endpoint，raw / compact / focus 三模式 |
| 章节展开 | 私有缓存保存原始 Markdown，`page_id + section_id` 可无网络 `expand` |
| MCP | 设备 localhost Streamable HTTP，Bearer token + Origin 校验 + 前台服务生命周期 |
| 广告过滤 | StevenBlack hosts 请求过滤 + EasyList/EasyList China DOM 清洗 + 在线更新 |

## 架构

```text
URL / 关键词 → SearchEngine(引擎URL模板) → WebPageLoader(共享WebView)
    → ContentExtractor(Readability+NoiseRules 去噪) → HtmlToMarkdown
    → StructuredAssembler(JSON 骨架)
    → compress/(raw 透传 | OpenAI-compatible compact/focus + PageExpansionCache)
    → ui/(网页/Markdown/JSON/Prompt 四 Tab)

engine/BrowserController: [eN] 快照 + click/type/scroll/annotate
engine/SearchEngine: 引擎注册表(bing/baidu/sogou/360/google)，URL 模板拼接
api/PageKitApi: MCP 工具契约（fetch/webSearch/expand/交互元素/浏览器控制）
engine/adblock/AdBlocker: StevenBlack hosts + EasyList cosmetic + WorkManager 更新
```

## 构建

```bash
# 一键脚本（自动配 JAVA_HOME/ANDROID_HOME，优先系统 adb）
./build.sh build            # debug APK
./build.sh test             # JVM 单测
./build.sh install [serial] # 安装到实机
./build.sh verify [serial]  # 实机全链路验证（加载→提取→md/json/prompt）
./build.sh mcptest [serial] # MCP 鉴权、Origin、握手与工具发现
./build.sh llmtest [serial] # compact/focus、确定性字段保真与 expand 闭环
./build.sh searchtest ["查询词"] ["https://www.bing.com"] [serial]  # 搜索结果页直航→提取（支持 baidu/bing/sogou/360/google）
./build.sh opentest ["查询词"] ["https://www.bing.com"] [serial]   # 搜索→点进第一条真实结果→详情页提取
./build.sh release          # release APK
./build.sh clean

# 手动方式：需 JDK 17 + Android SDK（platform-35 / build-tools 35）
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## adb 验证通道

```bash
# 加载页面并自动提取（结果落盘 files/last_result.txt）
adb shell am start -n com.kenjc.pagekit/.MainActivity \
  -d "file:///android_asset/test/testpage.html" --ez extract true

# 读取 Markdown / JSON
adb shell am broadcast -a com.kenjc.pagekit.FETCH_RESULT \
  -n com.kenjc.pagekit/.ResultTunnelReceiver
adb shell am broadcast -a com.kenjc.pagekit.FETCH_RESULT --es format json \
  -n com.kenjc.pagekit/.ResultTunnelReceiver

# 浏览器控制（结果落盘 files/control_result.txt，run-as 读取）
adb shell am start -a com.kenjc.pagekit.CONTROL --es op snapshot
adb shell am start -a com.kenjc.pagekit.CONTROL --es op type --es eid e4 --es text "关键词"
adb shell am start -a com.kenjc.pagekit.CONTROL --es op click --es eid e5
adb shell am start -a com.kenjc.pagekit.CONTROL --es op scroll --ei dy 400
adb shell am start -a com.kenjc.pagekit.CONTROL --es op prompt --es intent "部署指南"
adb shell run-as com.kenjc.pagekit cat files/control_result.txt

# 搜索引擎直航（关键词 → 结果页 URL 拼接 → 加载并提取）
adb shell am start -n com.kenjc.pagekit/.MainActivity \
  -a com.kenjc.pagekit.SEARCH --es query "RTX5090 部署" --es engine bing

# 单测（JVM）
./gradlew :app:testDebugUnitTest
```

## MCP（V2）

打开应用后，通知栏可见的前台服务会在设备回环地址提供 Streamable HTTP：
`http://127.0.0.1:3000/mcp`。服务只绑定回环地址，并要求应用首次启动时生成的
256-bit Bearer token；浏览器客户端的 `Origin` 也必须是 localhost/回环地址。

```bash
adb forward tcp:19300 tcp:3000
TOKEN=$(adb shell 'run-as com.kenjc.pagekit cat files/mcp_token.txt' | tr -d '\r\n')

# 一键验证 401/403、initialize、tools/list 和前台服务生命周期
./build.sh mcptest

# MCP Inspector 需要 Node.js >= 22.19；在 UI 的 Authentication 中填入 TOKEN
npx -y @modelcontextprotocol/inspector --web \
  --server-url http://127.0.0.1:19300/mcp --transport http \
  --header "Authorization: Bearer $TOKEN"
```

当前工具：`webfetch`、`websearch`、`expand`、`browser_snapshot`、`browser_click`、
`browser_type`、`browser_scroll`、`llm_status`、`llm_configure`。

`webfetch` / `websearch` 支持 `mode=raw|compact|focus`。raw 始终离线；compact/focus
使用在 Prompt 页或 `llm_configure` 中保存的 OpenAI-compatible endpoint/model/key。
Focus 必须提供 `intent`。LLM 的输出只采用 summary/key_points/sections 等语义字段，
代码、命令、表格、下载和链接会由本地确定性结果覆盖，避免模型改写原文。压缩结果携带
`page_id`，`remaining_information` 列出可传给 `expand` 的 `section_id`。

广告规则以 APK 内的固定快照作为永久兜底。WorkManager 在联网条件下每 7 天检查
StevenBlack hosts、EasyList 和 EasyList China，使用 ETag/Last-Modified 条件请求；
响应有 12 MiB 上限，须通过完整解析和最低规则数校验后才会原子写入私有缓存并热切换。

## 后续路线

- OkHttp 静态抓取快速通道

## 许可

- Readability.js：Apache-2.0（`app/src/main/assets/readability/`）
- StevenBlack hosts：MIT（固定规则快照见 `app/src/main/assets/adblock/`）
- EasyList / EasyList China：CC BY-SA 3.0（固定规则快照及署名见 `app/src/main/assets/adblock/`）
