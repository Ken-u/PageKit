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

给 AI Agent 联网看网页，常见的方案各有短板：

| | SearXNG | 收费搜索 API (Tavily/Serper…) | 服务器跑 headless 浏览器 | **PageKit** |
|---|---|---|---|---|
| 搜索 | ✓ | ✓ | ✗ | ✓ |
| 抓取网页内容 | 结果页链接 | 部分支持 | ✓ | ✓ |
| JS 动态渲染 | ✗ | 部分支持 | ✓ | ✓ |
| 浏览器操作 | ✗ | ✗ | ✓ | ✓ |
| 反爬 survivability | 差（服务器 IP） | 看厂商 | 差（服务器 IP） | 好（手机环境，和真人一致） |
| 登录态/Cookie | ✗ | ✗ | 单一 | 多 profile 隔离 |
| 广告过滤 | ✗ | ✗ | 需自己配 | 内置 |
| 持续成本 | 要服务器 | 按次付费 | 要服务器 | 一台旧手机/平板 |

一句话：搜、抓、操作 PageKit 全都能做，代价只是一台吃灰的旧设备。真机 WebView 环境，JS 渲染、动态加载、反爬都不是问题；多 profile 隔离，不同会话各自独立 Cookie，登录态也能保持。不收钱，不限次，不依赖外部服务。

### 反爬与实时性实测

在一台普通 Android 设备（rk3588 板子）上实测：

| 站点 | 检测类型 | 结果 |
|------|----------|------|
| bot.sannysoft.com | 浏览器指纹检测 | ✓ 通过 — WebDriver missing，WebGL 显示真实 GPU，无 headless 特征 |
| amazon.com | 重反爬电商 | ✓ 正常拿到商品页，无验证码 |
| nowsecure.nl | Cloudflare 防护 | ✓ 拿到正文 |
| time.is | 实时性 | ✓ 抓到的页面时间与本地实时一致，秒级新鲜 |
| reddit.com | 需登录 | ✗ 网络安全拦截（预期内，需登录态） |
| medium.com | CF 交互式挑战 | ✗ 卡在「Just a moment」 |

两点结论：

- **真机环境过反爬的能力是实打实的**：指纹检测页全绿（headless Chrome 会在这里挂掉 WebDriver 检测），Amazon、Cloudflare 被动防护都过了。强交互式挑战和需登录态的站点过不了——这不是短板，是所有无登录方案的边界，需要登录的站点可以走 profile 登录后再抓。
- **每次调用都是实时渲染**：没有缓存层，WebView 当场打开页面、当场提取。抓 time.is 这类秒级变化的页面，返回的时间就是当下的时间。搜索同理，返回的是搜索引擎此刻的结果，不是定期快照——很多搜索 API 是天级甚至更旧的缓存。

相比服务器端 headless 浏览器方案，优势不在绝对速度，而在**成功率和新鲜度**：真机指纹 + 无 headless 特征 + 实时渲染，不排队、不过缓存。

## 能做什么

- **网页抓取** — WebView 渲染完整页面后提取正文，JavaScript 动态内容也能拿到
- **广告过滤** — 内置 hosts 规则 + 元素隐藏规则，默认屏蔽广告域名和弹窗元素
- **内容压缩** — 可选接 OpenAI 兼容 LLM，把长页面压缩成摘要，省 token
- **并发会话** — 多 profile 隔离（独立 Cookie/缓存/进程），同时抓多个页面互不干扰
- **浏览器交互** — 点击、输入、滚动、下拉选择，模拟真人操作
- **闲置屏保** — 长时间不用自动进屏保全屏覆盖，防 OLED 烧屏；可设置超时时间、手动触发
- **WebView 代理** — 只影响 WebView 网络栈，不碰全局系统代理

## 快速开始

### 部署（推荐：不用搭构建环境）

从 release 页拿 APK，配合 `deploy.sh` 一条命令部署到设备——只要主机有 adb：

```bash
# 下载 deploy.sh 和 APK（或直接从 release 页下载）
curl -O https://raw.githubusercontent.com/Ken-u/PageKit/main/deploy.sh && chmod +x deploy.sh

./deploy.sh setup            # 一键：下载 APK → 安装 → 启动 → 打印连接信息
```

`deploy.sh` 其他命令：

| 命令 | 说明 |
|------|------|
| `setup` | 一键：下载/安装 APK → 启动 → 打印连接信息（含 Claude Code 接入命令） |
| `install [apk]` | 安装 APK（缺省自动从 GitHub latest release 下载） |
| `run` | 启动 App → 打印连接信息 |
| `token` | 打印设备上的 MCP token 和接入地址 |
| `set-token <token>` | 设置 token（换机/重装后恢复，客户端配置不用改） |
| `load` | 从 `.env` 读配置写入设备（token/代理/屏保/并发） |
| `forward` | adb 端口转发（3000/3001 → 本机） |

多台设备时 `PAGEKIT_DEVICE=<serial>` 指定；本地已有 APK 时 `PAGEKIT_APK=<路径>` 跳过下载。

### 从源码构建

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
./deploy.sh load   # 或 ./build.sh load [serial]
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

设备装好后，agent 通过下面两种方式之一访问：

**局域网直连（推荐，设备和 agent 在同一网段）：**

```bash
# 设备 IP 在 App 首页能看到，例如 192.168.1.100
http://192.168.1.100:3000/mcp
```

**adb 端口转发（设备在别处 / 不想暴露端口）：**

```bash
adb forward tcp:3000 tcp:3000  # 使用端（webfetch/websearch/browser_*）
adb forward tcp:3001 tcp:3001  # 管理端（profile/session/proxy/llm 管理）
# 然后访问 http://localhost:3000/mcp
```

`./build.sh run [serial] [url]` 可以一键构建→安装→启动→转发→打印连接信息。

## 接入 AI Agent

PageKit 提供两类接口，覆盖主流 agent 的接入方式：

| 接口 | 地址 | 适用 |
|------|------|------|
| MCP (streamable HTTP) | `http://<设备>:3000/mcp` | Claude Code / Cursor / Windsurf 等一切支持 MCP 的客户端 |
| HTTP API | `POST /v1/search`、`POST /v1/fetch` | 自研 agent、脚本、任何能发 HTTP 请求的程序 |

认证统一走 `Authorization: Bearer <token>`，token 在 App 首页或 `./build.sh token` 查看。

### Claude Code / Claude Desktop

```bash
claude mcp add --transport http pagekit http://<设备IP>:3000/mcp \
  --header "Authorization: Bearer <token>"
```

或直接编辑配置文件（`~/.claude.json`）：

```json
{
  "mcpServers": {
    "pagekit": {
      "type": "http",
      "url": "http://192.168.1.100:3000/mcp",
      "headers": { "Authorization": "Bearer <token>" }
    }
  }
}
```

接上后 agent 就有 `webfetch` / `websearch` / `browser_*` 可用。

### Cursor / Windsurf

MCP 设置里添加（Cursor: Settings → MCP → Add Server；Windsurf: 插件设置 → MCP Servers）：

```json
{
  "mcpServers": {
    "pagekit": {
      "serverUrl": "http://192.168.1.100:3000/mcp",
      "headers": { "Authorization": "Bearer <token>" }
    }
  }
}
```

### 任意支持 MCP 的客户端

PageKit 的 MCP 端点是标准 streamable HTTP 实现，任何符合 MCP 规范的客户端（Cline、Zed、OpenCode、goose 等）填 URL + Bearer token 即可，无需特殊适配。

### HTTP API（自研 agent / 脚本）

不想走 MCP 的，直接调 HTTP：

```bash
# 搜索（Kimi SearchWeb 兼容格式）
curl -X POST http://192.168.1.100:3000/v1/search \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{"text_query": "android mcp server"}'

# 抓取网页（返回提取后的 markdown 正文）
curl -X POST http://192.168.1.100:3000/v1/fetch \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{"url": "https://example.com"}'
```

`/v1/search` 是 Kimi 原生 SearchWeb 的兼容端点——Kimi 用户把 SearchWeb provider 的地址指过来就能用。两个接口都支持可选 header `X-PageKit-Session` / `X-PageKit-Profile`，不传则自动分配会话并在设备 UI 上实时显示渲染过程。

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
