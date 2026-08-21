# Coding Agent WebSearch 接入

PageKit 在同一个设备回环服务上提供两种 adapter：

- 通用 MCP：`POST http://127.0.0.1:3000/mcp`
- Kimi Code 原生 `SearchWeb`：`POST http://127.0.0.1:3000/v1/search`

两者共用 PageKit 的 WebView 登录态、搜索引擎、广告过滤和串行浏览器会话。服务只绑定设备
localhost；主机侧先执行：

```bash
adb forward tcp:19300 tcp:3000
TOKEN=$(adb shell 'run-as com.kenjc.pagekit cat files/mcp_token.txt' | tr -d '\r\n')
```

## Kimi Code：替换内置 SearchWeb

在 `~/.kimi/config.toml` 中加入（保留文件内已有的 provider/model 配置）：

```toml
[services.moonshot_search]
base_url = "http://127.0.0.1:19300/v1/search"
api_key = "这里替换为 PageKit TOKEN"
```

Kimi 会自动发送 `Authorization: Bearer <api_key>`。PageKit adapter 完整支持其原生契约：

```json
{
  "text_query": "Kotlin MCP server",
  "limit": 5,
  "enable_page_crawling": false,
  "timeout_seconds": 30
}
```

返回 `search_results[]`，每项都包含 `site_name/title/url/snippet/content/date/icon/mime`。
`enable_page_crawling=true` 时会依次打开结果页并尝试提取 Markdown；单个目标页不可访问时该项
`content` 为空，但不会丢掉搜索结果。共享 WebView 必须串行工作，建议同时把 `limit` 控制在 3–5。

也可以不覆盖内置工具，直接把 PageKit 作为 MCP server 加入 Kimi：

```bash
kimi mcp add --transport http pagekit http://127.0.0.1:19300/mcp \
  --header "Authorization: Bearer $TOKEN"
kimi mcp test pagekit
```

## Claude Code / Cursor / VS Code / 其他 Agent

优先使用 MCP adapter。Kimi、Claude Code、Cursor 等客户端常用的 `mcpServers` 配置结构
如下；把 token 替换为实际值：

```json
{
  "mcpServers": {
    "pagekit": {
      "type": "http",
      "url": "http://127.0.0.1:19300/mcp",
      "headers": {
        "Authorization": "Bearer PAGEKIT_TOKEN"
      }
    }
  }
}
```

VS Code 的 `.vscode/mcp.json` 使用相同 server 内容，但顶层键名是 `servers`。不同客户端只需
按自身格式放入 MCP 配置；PageKit 不依赖某个 Agent SDK。连接后会发现 `websearch`、
`webfetch`、`expand` 和浏览器控制工具。

## 自定义 adapter

厂商协议映射实现 `WebSearchProvider`，无需依赖 Android/Ktor：

```kotlin
fun interface WebSearchProvider {
    suspend fun search(request: WebSearchRequest): WebSearchResponse
}
```

标准请求包含 `query/limit/includeContent/engine`，标准结果包含上述八个搜索字段。新增厂商时只需：

1. 将厂商请求 DTO 映射成 `WebSearchRequest`；
2. 调用 `provider.search(...)`；
3. 将 `WebSearchResponse` 映射成厂商响应 DTO。

不要在 adapter 中直接操作 WebView，以免绕过 PageKit 的会话互斥锁和广告规则。

## 验证

```bash
./build.sh test
./build.sh providertest
```
