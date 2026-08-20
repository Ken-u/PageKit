# PageKit V1 计划

> WebFetch 信息压缩引擎（SPECS.md）的 Android 实现。
> V1 定位：**独立 App**，打通「URL → WebView 完整渲染 → 主内容提取 → 去噪 → Markdown / 结构化输出」管线；LLM 压缩与 MCP 服务的**接口契约 V1 预留**，V2 接入。

## 已确认的决策

| 决策项 | 结论 |
| --- | --- |
| 产品形态 | 独立 App（界面输入 URL，查看结果） |
| LLM | V1 不接入，只做渲染 + 提取 + 去噪；Compressor 接口预留 |
| 网页获取 | 应用内 WebView 完整渲染（JS、Cookie/登录态可用，真实可见可交互） |
| MCP | V1 不跑 server，但冻结**工具级 API 契约**（webfetch / expand / 交互元素 / 浏览器控制），UI 即首个客户端，V2 薄封装即可上 MCP；远期亦可反哺本 Agent 环境自身的 web 工具 |
| 技术栈 | Kotlin + Jetpack Compose + MVVM，minSdk 26 |

## V1 范围

### 做

1. URL 输入 → 应用内 WebView 加载渲染（JS 执行完毕、Cookie 可用）
2. 主内容提取（Readability 启发式 + 兜底策略）
3. 按 SPECS.md 忽略清单去噪（导航/Footer/Sidebar/广告/评论区等）
4. HTML → Markdown 转换（保留代码块、表格、链接原文）
5. 规则化结构化输出：JSON 骨架中**确定性字段**直接填（`title`/`url`/`code_blocks`/`tables`/`commands`/`links`/`downloads`），语义字段（`summary`/`key_points`/`sections` 等）置 null，等 V2 LLM 填
6. 顶部按钮 Tab 切换视图：**网页**（真实 WebView，可滚动、可交互、可登录）/ **Markdown** / **JSON**；结果支持复制 / 分享导出
7. `Compressor` 接口 + `NoopCompressor` + `PromptBuilder`（按 SPECS.md 拼 System/Developer/User Prompt），提供「复制完整 Prompt」调试入口——V1 就能手动贴给任意 LLM 验证输出
8. 登录态：直接在「网页」Tab 内完成登录，CookieManager 全局共享，提取管线复用同一 WebView（不再需要独立的内置浏览器页）
9. MCP 接口预留：`api/PageKitApi` 门面 + 与 SPECS.md JSON Schema 一致的 DTO；UI 与未来的 MCP server 都走这层；`expand()` V1 返回 `NotImplemented`（依赖章节缓存），其余工具 V1 全部可用
10. 浏览器控制：`list_interactive_elements`（枚举网页内可交互元素并编号，如 `[e1] button Copy`）+ `click(e)` / `type(e, text)` / `scroll(...)`；网页 Tab 提供「元素标注」调试开关，可视化编号并可快捷触发操作
11. 去广告 SPI 预留：`AdBlocker` 接口（请求级 `shouldBlock(request)` + DOM 级 `elementHidingRules()` 并入 NoiseFilter），V1 默认 `NoopAdBlocker`（仅 NoiseFilter 内置广告选择器生效）；V2 直接挂 hosts 黑名单 / EasyList 元素隐藏实现

### 不做（V2+）

- LLM 真正执行压缩（Compact 模式的语义压缩、Focus 模式的意图过滤）
- `remaining_information` / `expand("章节")` 联动
- OkHttp 静态抓取快速通道
- 历史记录、多会话管理
- MCP 服务暴露（V1 只冻结工具契约，不启动 server）
- 开源广告规则库接入（StevenBlack hosts / EasyList / AdGuard，V2 经 `AdBlocker` SPI 挂载）

> 注：V1 的「去噪 + 全保留」本质上就是 SPECS.md 里的 **Raw 模式**，V2 接 LLM 后自然升级出 Compact/Focus。

## 架构

单模块 `:app` 起步，包结构按未来抽 library 的边界划分：

```text
com.kenjc.pagekit/
├── ui/            # Compose 界面 + ViewModel（MVVM）
│   └── home/      # 单屏：URL 栏 + 顶部 Tab（网页 WebView / Markdown / JSON）+ 提取按钮
├── api/           # 对外契约层：UI 与未来 MCP server 共用的门面
│   ├── PageKitApi.kt           # fetch / expand / listInteractiveElements / click / type / scroll
│   └── dto/                    # FetchRequest、CompressedPage（字段=SPECS.md Schema）
├── engine/        # 核心管线（无 Android UI 依赖，便于未来抽库；实现 api/ 接口）
│   ├── WebPageLoader.kt        # 共享 WebView：加载、空闲判定、超时、错误
│   ├── BrowserController.kt    # 元素快照枚举 [eN] + click/type/scroll 事件派发
│   ├── ContentExtractor.kt     # 注入 Readability.js 提取主内容 HTML
│   ├── NoiseFilter.kt          # SPECS.md 忽略清单 → 选择器规则表
│   ├── HtmlToMarkdown.kt       # flexmark html2md 封装
│   └── StructuredAssembler.kt  # 规则化填充 JSON 骨架
├── adblock/       # 去广告 SPI（V1 预留，V2 挂开源规则库）
│   ├── AdBlocker.kt           # 接口：shouldBlock(request) + elementHidingRules()
│   └── NoopAdBlocker.kt       # V1 默认实现：全放行（去噪仍由 NoiseFilter 承担）
├── compress/
│   ├── Compressor.kt           # 接口：compress(page): Result
│   ├── PromptBuilder.kt        # SPECS.md 三段 Prompt 拼装
│   └── NoopCompressor.kt       # V1 占位实现
└── App.kt
```

数据流：

```text
URL → WebPageLoader(共享WebView，即「网页」Tab) → 完整HTML
    → ContentExtractor(主内容HTML) → NoiseFilter(去噪)
    → HtmlToMarkdown(Markdown)
    → StructuredAssembler(JSON骨架)
    → PageKitApi 门面
    → ViewModel → UI 展示 / 导出（V2：MCP server 调同一门面）
```

提取由「提取」按钮手动触发（页面可能需要先登录或滚动加载），空闲判定只用于提示「页面已就绪」。

## 关键技术方案

- **共享 WebView**：单一实例 attach 在「网页」Tab（真实可见、可交互），登录/验证码直接在 Tab 内完成；管线对该实例 `evaluateJavascript` 提取，无需二次加载；空闲判定 = `onProgressChanged==100` + `document.readyState=="complete"` + 静默 800ms（用于「已就绪，可提取」提示）；超时 20s；错误页/网络错误回调处理
- **主内容提取**：WebView `evaluateJavascript` 注入 Readability.js（asset 打包），失败兜底 `<main>`/`<article>`/最大文本块启发式
- **HTML→Markdown**：flexmark `html2md-converter`（纯 JVM、Android 可用、表格支持好），代码块保持原文 fence
- **去噪**：`NoiseRule(selector, reason)` 规则表，与 SPECS.md 忽略清单一一对应，便于对照维护
- **去广告（SPI 预留）**：双层设计——请求级走 `WebViewClient.shouldInterceptRequest` 查域名黑名单（hosts 格式，候选 **StevenBlack 统一列表**，MIT 许可，约 9.3 万域名，V2 打包资产 + 在线更新）；DOM 级元素隐藏选择器并入 NoiseFilter（V2 可载 **EasyList** 元素隐藏子集，注意其 CC BY-SA/GPLv3 类许可对对外发布形态的影响，内部使用无碍）；V1 只冻结 `AdBlocker` 接口形状 + `NoopAdBlocker`，接口语义对齐 uBlock 类工具，接入成本低
- **JSON**：kotlinx-serialization，Schema 字段与 SPECS.md 完全一致
- **MCP 契约预留**：工具面冻结为 `webfetch(url, intent?, mode)` / `expand(section)` / `list_interactive_elements` / `click` / `type` / `scroll`，V1 除 `expand` 外全部实现；DTO 序列化结果即 MCP tool payload，V2 server 层只做协议薄封装
- **浏览器控制**：JS 注入枚举可交互元素（button/input/select/a 及 ARIA role 控件），生成单次快照内稳定的编号 `[eN]`；`click`/`type` 经 JS 派发真实事件，`scroll` 用 `window.scrollBy`；编号仅在快照有效期内可用，操作前必须先取快照，元素失配即报错让调用方重取；网页 Tab「元素标注」开关可视化编号，便于人工验证

## 验证环境

- 构建机：Ubuntu 22.04（JDK 11 默认，需另装 JDK 17 供 AGP 8.x；Gradle 与 Android SDK 需自行搭建到 `~/.sdk/`）
- 实机验证：adb 在线设备 3 台（含 rk3588），APK 安装与启动均以实机为准：`adb install -r` + `adb shell am start` + logcat 无 crash
- 网络受限备选：SDK 用腾讯镜像（`mirrors.cloud.tencent.com/AndroidSDK/`），Maven 用阿里云镜像（google/public/gradle-plugin）

## 里程碑与验收标准

| # | 里程碑 | 验收标准 |
| --- | --- | --- |
| M1 | 工程骨架 | Gradle KTS + version catalog 编译通过；Compose 壳：URL 输入 + 顶部三 Tab（网页/Markdown/JSON 占位）可运行 |
| M2 | WebView 渲染管线 | 「网页」Tab 加载并显示真实页面；就绪提示 + 手动「提取」按钮（本阶段先回显原始 HTML 长度/耗时）；超时/网络错误有明确状态；`AdBlocker` SPI 签名冻结（默认 Noop，`shouldInterceptRequest` 挂点预留） |
| M3 | 提取 + 去噪 | 3 类典型页面（技术文档 / 带代码教程 / 普通文章）输出干净 Markdown，无导航/广告残留 |
| M4 | 结构化输出 + 导出 | JSON 骨架确定性字段填充正确（`interactive_elements` 留空，M5 元素枚举上线后填充）；复制/分享可用 |
| M5 | 浏览器控制 | 「元素标注」开启后可见 `[eN]` 编号；click/type/scroll 经 API 与标注开关均可触发且页面正确响应；操作后可重新提取 |
| M6 | Prompt & MCP 预留 | `Compressor` 接口落地；`PageKitApi` 工具签名与 DTO 冻结（`expand` 返回 NotImplemented）；「复制完整 Prompt」可导出 SPECS.md 三段 Prompt |
| M7 | 打磨与测试 | NoiseFilter / HtmlToMarkdown / 元素枚举单测；错误态 UI；README |

## 依赖清单

- Compose BOM、Activity Compose、ViewModel Compose、Lifecycle
- kotlinx-coroutines
- kotlinx-serialization-json
- flexmark-html2md-converter
- Readability.js（本地 asset）
- test: junit、robolectric（NoiseFilter 单测）

## 风险与备选

| 风险 | 备选 |
| --- | --- |
| 部分站点反爬 | WebView 真实可见、可交互，等同用户手刷；必要时自定义 UA |
| Readability 提取失败（非文章页） | 兜底 `<main>`/`<article>`/最大文本块，再不行退回全页去噪 |
| 表格/代码块转换质量 | flexmark 配置调优；代码块一律原文保留不转换 |
| 登录态站点 | 「网页」Tab 内直接登录，CookieManager 全局共享 |
| 动态页面元素编号漂移 | 编号绑定单次快照，操作前强制刷新快照；元素失配即报错，调用方重取 |
| 广告规则误杀正常域名/元素 | SPI 支持白名单与总开关；V1 Noop 默认无误杀 |
| EasyList/AdGuard 规则许可（CC BY-SA / GPLv3 类） | 内部使用无碍；对外分发前复核，或仅用 MIT 的 hosts 类列表 |

## 待确认

- 包名 `com.kenjc.pagekit`、应用名 **PageKit**（仓库目录名），如需改现在说
- targetSdk 35、AGP/Kotlin 用当前稳定版
- 顶部 Tab 定为「网页 / Markdown / JSON」三个；M5 的 Prompt 输出届时是加第四个 Tab 还是并入 JSON，到时再定
- MCP 工具集冻结为 `webfetch` / `expand` / `list_interactive_elements` / `click` / `type` / `scroll`，除 `expand` 外 V1 全实现——如需增减现在定
