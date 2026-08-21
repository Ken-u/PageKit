<p align="center">
  <img src="docs/assets/pagekit-icon.png" width="128" height="128" alt="PageKit icon">
</p>

<h1 align="center">PageKit</h1>

<p align="center">
  <a href="https://github.com/Ken-u/PageKit/actions/workflows/android.yml"><img src="https://github.com/Ken-u/PageKit/actions/workflows/android.yml/badge.svg" alt="Android CI"></a>
  <img src="https://img.shields.io/badge/Android-9%2B-3DDC84?logo=android&logoColor=white" alt="Android 9+">
  <img src="https://img.shields.io/badge/Kotlin-2.x-7F52FF?logo=kotlin&logoColor=white" alt="Kotlin 2.x">
</p>

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
| Coding Agent adapter | 标准 `WebSearchProvider` + Kimi Code 原生 SearchWeb HTTP 协议；其他 Agent 走 MCP |
| Session 隔离 | 每个 Session 独立 WebView、导航、DOM、JS Context、元素编号与操作互斥锁 |
| Profile 隔离 | 3 个独立 Android worker 进程 + `WebView.setDataDirectorySuffix`，隔离 Cookie/存储/缓存 |
| 广告过滤 | StevenBlack hosts 请求过滤 + EasyList/EasyList China DOM 清洗 + 在线更新 |

## 架构

```text
Agent → ProfileRouter(default / :profile1 / :profile2 / :profile3)
    → SessionRegistry → 每 Session 一个 WebView + Mutex
URL / 关键词 → SearchEngine(引擎URL模板) → WebPageLoader(Session WebView)
    → ContentExtractor(Readability+NoiseRules 去噪) → HtmlToMarkdown
    → StructuredAssembler(JSON 骨架)
    → compress/(raw 透传 | OpenAI-compatible compact/focus + PageExpansionCache)
    → ui/(网页/Markdown/JSON/Prompt 四 Tab)

engine/BrowserController: [eN] 快照 + click/type/scroll/annotate
engine/SearchEngine: 引擎注册表(bing/baidu/sogou/360/google)，URL 模板拼接
api/PageKitApi: MCP 工具契约（fetch/webSearch/expand/交互元素/浏览器控制）
session/: 多 WebView 注册表、Session/Profile 路由
profile/: AIDL + ParcelFileDescriptor 跨进程通道、独立 WebView data directory
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
./build.sh providertest [serial] # Kimi SearchWeb provider 鉴权与协议结构
./build.sh sessiontest [serial] # 多 WebView DOM + 多进程 Profile/Cookie 隔离
./build.sh llmtest [serial] # compact/focus、确定性字段保真与 expand 闭环
./build.sh searchtest ["查询词"] ["https://www.bing.com"] [serial]  # 搜索结果页直航→提取（支持 baidu/bing/sogou/360/google）
./build.sh opentest ["查询词"] ["https://www.bing.com"] [serial]   # 搜索→点进第一条真实结果→详情页提取
./build.sh release          # 使用本地固定 keystore 构建签名 release APK
./build.sh clean

# 手动方式：需 JDK 17 + Android SDK（platform-35 / build-tools 35）；minSdk 28
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 版本与 GitHub CI

应用版本只有一个修改入口：[gradle.properties](gradle.properties) 中的 `PAGEKIT_VERSION`：

```properties
PAGEKIT_VERSION=0.4.0
```

版本采用 SemVer。Android `versionCode` 根据 `major * 1,000,000 + minor * 1,000 + patch`
自动生成，不需要再手工同步。修改版本并推送后，
[Android CI](https://github.com/Ken-u/PageKit/actions/workflows/android.yml) 会自动：

1. 使用 JDK 17 运行 JVM 单元测试；
2. 编译可直接安装的 debug APK；
3. 使用仓库 Secrets 中的固定私钥编译并校验签名 release APK；
4. 为两种 APK 生成 SHA-256，并上传 Actions Artifact。

Pull Request 不读取签名 Secrets，只验证单测和 debug 构建。普通 branch push 会额外保存 90 天的
`PageKit-v<version>-<commit>-release.apk`；`v*` tag 构建成功后还会自动创建 GitHub Release，
发布签名 APK 和 SHA-256。tag 必须与版本严格一致，例如 `PAGEKIT_VERSION=0.4.0` 对应
`v0.4.0`，不一致时 CI 会明确失败。

Release 签名使用以下 GitHub Actions Secrets，私钥和密码均不得提交到仓库：

- `PAGEKIT_KEYSTORE_BASE64`
- `PAGEKIT_KEYSTORE_PASSWORD`
- `PAGEKIT_KEY_ALIAS`
- `PAGEKIT_KEY_PASSWORD`

本地 `./build.sh release` 默认读取已忽略的 `release/pagekit-release.jks` 和
`release/.keystore-password`。这个 keystore 是后续 APK 更新的唯一身份，必须离线备份；丢失后
无法再为已安装版本签发可升级 APK。

当前 Release 证书 SHA-256：
`2F:A5:2D:87:29:6F:2B:88:31:90:E1:37:89:D2:17:E9:F1:EE:F1:9B:18:34:82:86:60:B3:E2:B0:B1:98:9A:23`。
早期 CI debug APK 使用不同证书，首次切换到 Release APK 需要卸载旧 debug 版本；之后签名
固定，可直接覆盖升级。

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
`browser_type`、`browser_scroll`、`profile_create`、`profile_list`、`profile_delete`、
`session_create`、`session_list`、`session_close`、`llm_status`、`llm_configure`。

页面和浏览器工具均接受可选 `session_id`，缺省使用 UI 的 `default` Session。隔离 Profile
最多 3 个，每个 Profile 最多 4 个 WebView Session；同 Profile 的 Session 共享 Cookie，
不同 Profile 使用独立进程和 WebView 数据目录。完整语义和调用顺序见
[Session 与 Profile 隔离](docs/sessions-and-profiles.md)。

`webfetch` 支持 `mode=raw|compact|focus`。raw 始终离线；compact/focus
使用在 Prompt 页或 `llm_configure` 中保存的 OpenAI-compatible endpoint/model/key。
Focus 必须提供 `intent`。LLM 的输出只采用 summary/key_points/sections 等语义字段，
代码、命令、表格、下载和链接会由本地确定性结果覆盖，避免模型改写原文。压缩结果携带
`page_id`，`remaining_information` 列出可传给 `expand` 的 `section_id`。

MCP `websearch` 复用标准 `WebSearchProvider`，参数为 `query / engine / limit /
include_content`，直接返回结构化 `results[]`；`include_content=true` 时会抓取每条结果正文。

## Coding Agent WebSearch adapter

除 MCP 外，同一服务还提供 Kimi Code 原生 `SearchWeb` endpoint：
`http://127.0.0.1:3000/v1/search`。它接受 Kimi 的 `text_query / limit /
enable_page_crawling / timeout_seconds` 请求，并返回严格的 `search_results[]` 契约。
主机经 `adb forward` 后使用 `http://127.0.0.1:19300/v1/search`，Bearer token 与 MCP 相同。

Kimi 原生配置、Kimi MCP 命令、通用 MCP JSON，以及新增其他厂商 adapter 的方式见
[Coding Agent WebSearch 接入](docs/coding-agent-websearch.md)。

广告规则以 APK 内的固定快照作为永久兜底。WorkManager 在联网条件下每 7 天检查
StevenBlack hosts、EasyList 和 EasyList China，使用 ETag/Last-Modified 条件请求；
响应有 12 MiB 上限，须通过完整解析和最低规则数校验后才会原子写入私有缓存并热切换。

## 许可

- Readability.js：Apache-2.0（`app/src/main/assets/readability/`）
- StevenBlack hosts：MIT（固定规则快照见 `app/src/main/assets/adblock/`）
- EasyList / EasyList China：CC BY-SA 3.0（固定规则快照及署名见 `app/src/main/assets/adblock/`）
