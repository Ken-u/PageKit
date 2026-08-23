<p align="center">
  <img src="docs/assets/pagekit-icon.png" width="128" height="128" alt="PageKit icon">
</p>

<h1 align="center">PageKit</h1>

<p align="center">
  <a href="https://github.com/Ken-u/PageKit/actions/workflows/android.yml"><img src="https://github.com/Ken-u/PageKit/actions/workflows/android.yml/badge.svg" alt="Android CI"></a>
  <img src="https://img.shields.io/badge/Android-9%2B-3DDC84?logo=android&logoColor=white" alt="Android 9+">
  <img src="https://img.shields.io/badge/Platform-Android%20TV%20%7C%20Tablet-3DDC84" alt="Android TV / Tablet">
  <img src="https://img.shields.io/badge/License-Apache--2.0-blue" alt="License">
</p>

抽屉里吃灰的旧 Android 手机/平板，插上电就是一台 AI Agent 专属的浏览器。

PageKit 在 Android 设备上跑一个 MCP server，用真实 WebView 给 AI Agent 提供稳定可靠的 web search / web fetch 能力。Agent 通过标准 MCP 协议远程调用，设备负责打开网页、过滤广告、提取干净的结构化内容返回。自带多会话并发、浏览器自动化交互、闲置屏保防烧屏。

> 旧设备不闲置，AI 不缺联网。

## 和其他方案的区别

| | SearXNG / 免费 API | 收费搜索 API | **PageKit** |
|---|---|---|---|
| 运行方式 | 自建/公共实例 | 云端 SaaS | 旧 Android 设备本地跑 |
| 动态页面 | 大多拿不到 JS 渲染结果 | 部分支持 | 真实 WebView 完整渲染 |
| 反爬限制 | IP 容易被封 | 有 quota / 按次收费 | 手机环境，和真人浏览器一致 |
| 持续成本 | 需要服务器 | 按调用付费 | 电费而已 |
| 浏览器交互 | 不支持 | 不支持 | 点击/输入/滚动/选择 |
| Cookie/登录态 | 不支持 | 不支持 | 多 profile 隔离，各自独立 |
| 广告过滤 | 无 | 无 | 内置 hosts + 元素隐藏 |

说直白点：SearXNG 和搜索 API 解决的是「搜」的问题，拿到的是搜索引擎结果页。PageKit 解决的是「看」的问题——用真机 WebView 把网页完整打开，JS 渲染、登录态、动态加载都跑完，再把干净内容提取出来给 agent。对反爬严的站点，手机环境比服务器 IP 靠谱得多。

两者不冲突，可以组合用：SearXNG 搜索拿 URL 列表，PageKit 负责把每个 URL 的内容抓干净。

## 能做什么

- **网页抓取** — WebView 渲染完整页面后提取正文，JavaScript 动态内容也能拿到
- **广告过滤** — 内置 hosts 规则 + 元素隐藏规则，默认屏蔽广告域名和弹窗元素
- **内容压缩** — 可选接 OpenAI 兼容 LLM，把长页面压缩成摘要，省 token
- **并发会话** — 多 profile 隔离（独立 Cookie/缓存/进程），同时抓多个页面互不干扰
- **浏览器交互** — 点击、输入、滚动、下拉选择，模拟真人操作
- **闲置屏保** — 长时间不用自动进屏保全屏覆盖，防 OLED 烧屏；可设置超时时间、手动触发
- **WebView 代理** — 只影响 WebView 网络栈，不碰全局系统代理

## 快速开始

### 构建

```bash
./build.sh release        # 构建签名 release APK
./build.sh install [serial]  # 构建并安装到设备
```

首次构建需要 JDK 17+ 和 Android SDK。`local.properties` 里配 `sdk.dir`。

### 配置

把 `.env.example` 复制为 `.env`，填好 token 和代理后一键写入设备：

```bash
cp .env.example .env
# 编辑 .env，填入 token、代理地址等
./build.sh load [serial]   # 从 .env 读取配置写入设备
```

`.env` 已在 `.gitignore` 里，不会入库。支持配置项：

| 变量 | 说明 |
|------|------|
| `PAGEKIT_TOKEN` | MCP 认证 token，重装/换机后恢复配置 |
| `PAGEKIT_PROXY_ENABLED` | 是否启用 WebView 代理 |
| `PAGEKIT_PROXY_HOST` | 代理地址 |
| `PAGEKIT_PROXY_PORT` | 代理端口 |
| `PAGEKIT_PROXY_BYPASS` | 不走代理的域名（分号分隔） |
| `PAGEKIT_SCREENSAVER_TIMEOUT_MS` | 屏保触发时间（毫秒） |
| `PAGEKIT_MAX_SESSIONS` | 最大并发会话数 |

也可以直接在 App 设置界面里改。

### 连接

设备装好后，用 adb 转发端口：

```bash
adb forward tcp:3000 tcp:3000  # 使用端（webfetch/websearch/browser_*）
adb forward tcp:3001 tcp:3001  # 管理端（profile/session/proxy/llm 管理）
```

agent 侧配置 MCP server URL 为 `http://localhost:3000/mcp`，Authorization header 带上 token 就行。

`./build.sh run [serial] [url]` 可以一键构建→安装→启动→转发→打印连接信息。

## MCP 工具

PageKit 暴露两组 MCP 工具，分别跑在 3000 和 3001 端口：

**使用端（3000）— agent 实际调用的：**

| 工具 | 说明 |
|------|------|
| `webfetch` | 抓取网页，返回去广告后的结构化内容 |
| `websearch` | 搜索引擎查询，返回结果列表 |
| `expand` | 展开页面的某个分区（分块返回长内容） |
| `browser_navigate` | 导航到指定 URL |
| `browser_snapshot` | 获取当前页面可交互元素快照 |
| `browser_click` | 点击元素 |
| `browser_type` | 在输入框输入文字 |
| `browser_scroll` | 滚动页面 |
| `browser_select` | 下拉选择 |
| `browser_back` | 后退 |
| `browser_url` | 获取当前 URL |

**管理端（3001）— 管理 agent 用的：**

| 工具 | 说明 |
|------|------|
| `profile_create` / `profile_delete` / `profile_list` | profile 管理 |
| `session_create` / `session_close` / `session_close_idle` / `session_list` | 会话管理 |
| `proxy_configure` / `proxy_status` | 代理配置 |
| `llm_configure` / `llm_status` | LLM 压缩器配置 |

不传 `session_id` 时自动分配并发 session 并在 UI 上实时显示，传 `session_id` 则固定到指定会话。

## 架构

```
┌─────────────────────────────────────────────┐
│                PageKit App                   │
│  ┌───────────┐  ┌────────────────────────┐  │
│  │  主进程    │  │  Profile Worker 进程    │  │
│  │  (default) │  │  (:profile1/2/3)       │  │
│  │            │  │                        │  │
│  │  WebView   │  │  WebView (独立 Cookie)  │  │
│  │  MCP Server│  │  独立 data-dir          │  │
│  │  UI/屏保   │  │                        │  │
│  └─────┬─────┘  └───────────┬────────────┘  │
│        │    AIDL/binder      │              │
│        └──────────┬──────────┘              │
│                   │                         │
│         ┌─────────┴─────────┐               │
│         │  AdBlocker        │               │
│         │  ContentExtractor │               │
│         │  LLM Compressor   │               │
│         └───────────────────┘               │
└─────────────────────────────────────────────┘
         │ port 3000 (使用端)
         │ port 3001 (管理端)
    MCP / HTTP / SSE
         │
    AI Agent (Claude / GPT / ...)
```

- **多进程隔离**：每个 profile 跑在独立进程，WebView data-directory、Cookie、缓存完全隔离
- **广告过滤**：hosts 规则拦截网络请求 + CSS 元素隐藏规则，规则可配置自动更新
- **内容提取**：Readability.js 提取正文，fallback 到语义化标签；沙盒页面（如 raw text）走原生 HTTP 回退
- **固定 token**：token 持久化存储，重装不变，除非手动重置

## build.sh 子命令

| 命令 | 说明 |
|------|------|
| `build` | 构建 debug APK |
| `release` | 构建签名 release APK |
| `install [serial]` | 构建并安装 |
| `run [serial] [url]` | 一键构建→安装→启动→转发→打印信息 |
| `load [serial]` | 从 .env 读取配置写入设备 |
| `token [serial]` | 打印设备上的 MCP token |
| `set-token <token> [serial]` | 设置设备 MCP token |
| `test` | 跑单元测试 |
| `verify [serial]` | 端到端验证 |
| `mcptest [serial]` | MCP 协议测试 |
| `sessiontest [serial]` | 并发会话测试 |
| `clean` | 清理构建产物 |

## 技术栈

- Kotlin + Jetpack Compose
- AndroidX WebKit（ProxyController 代理）
- Ktor server（MCP over HTTP/SSE）
- Readability.js（内容提取）
- AIDL 多进程通信

## License

Apache-2.0
