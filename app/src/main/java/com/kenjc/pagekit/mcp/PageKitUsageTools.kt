package com.kenjc.pagekit.mcp

import com.kenjc.pagekit.api.dto.FetchRequest
import com.kenjc.pagekit.provider.SessionWebSearchProvider
import com.kenjc.pagekit.provider.WebSearchRequest
import com.kenjc.pagekit.session.DEFAULT_SESSION_ID
import com.kenjc.pagekit.session.PageKitSessionGateway
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.net.URI

/**
 * 使用端 MCP tools：网页渲染、搜索、章节展开、浏览器操作。
 * 挂载在 `/mcp` 端点，面向实际使用和接入的 Agent。
 */
class PageKitUsageTools(
    private val gateway: PageKitSessionGateway,
) {

    fun createServer(): Server = Server(
        serverInfo = Implementation(name = "pagekit", version = "0.4.0"),
        options = ServerOptions(
            capabilities = ServerCapabilities(
                tools = ServerCapabilities.Tools(listChanged = false),
            ),
        ),
        instructions = "Render pages in Android WebView, remove ads/noise, and extract structured content. Use browser tools to interact with loaded pages.",
    ).apply {
        val h = McpToolHelpers

        addTool(
            name = "webfetch",
            description = "Render and extract an HTTP(S) page in raw, compact, or intent-focused mode.",
            inputSchema = h.schema(
                "url" to h.stringProperty("HTTP(S) URL"),
                "intent" to h.stringProperty("Optional extraction intent"),
                "mode" to h.stringProperty("Extraction mode", enum = listOf("raw", "compact", "focus")),
                "session_id" to h.sessionProperty(),
                required = listOf("url"),
            ),
        ) { request ->
            val args = request.arguments
            h.toolResult {
                val url = h.requiredStr(args, "url")
                validateRemoteUrl(url)
                val mode = h.str(args, "mode") ?: "raw"
                validateMode(mode, h.str(args, "intent"))
                val fetchRequest = FetchRequest(
                    url = url,
                    intent = h.str(args, "intent"),
                    mode = mode,
                )
                // 未指定 session_id：从空闲池自动分配（并行友好），并回显以便后续固定路由。
                val (sessionId, result) = h.str(args, "session_id")?.let {
                    it to gateway.fetch(it, fetchRequest)
                } ?: gateway.fetchAuto(fetchRequest)
                buildJsonObject {
                    put("session_id", sessionId)
                    put("page", h.json.encodeToJsonElement(result.page))
                    put("markdown", result.markdown)
                    put("byline", result.byline)
                    put("extraction_mode", result.extractionMode)
                    put("compression_mode", mode)
                    put("duration_ms", result.durationMs)
                }
            }
        }

        addTool(
            name = "websearch",
            description = "Search the web and return structured results; optionally render each result and include its Markdown content.",
            inputSchema = h.schema(
                "query" to h.stringProperty("Search query"),
                "engine" to h.stringProperty("Search engine", enum = listOf("bing", "baidu", "sogou", "360", "google")),
                "limit" to h.integerProperty("Number of results (1-20)", default = 5),
                "include_content" to h.booleanProperty("Render result pages and include Markdown content", default = false),
                "session_id" to h.sessionProperty(),
                required = listOf("query"),
            ),
        ) { request ->
            val args = request.arguments
            h.toolResult {
                val startedAt = System.currentTimeMillis()
                val result = SessionWebSearchProvider(gateway, h.str(args, "session_id") ?: DEFAULT_SESSION_ID).search(
                    WebSearchRequest(
                        query = h.requiredStr(args, "query"),
                        engine = h.str(args, "engine") ?: "bing",
                        limit = h.intArg(args, "limit") ?: 5,
                        includeContent = h.boolArg(args, "include_content") ?: false,
                    ),
                )
                buildJsonObject {
                    put("query", result.query)
                    put("results", h.json.encodeToJsonElement(result.results))
                    put("duration_ms", System.currentTimeMillis() - startedAt)
                }
            }
        }

        addTool(
            name = "expand",
            description = "Return one original Markdown section from the page cache without fetching the page again.",
            inputSchema = h.schema(
                "section" to h.stringProperty("Section ID (such as s2) or heading"),
                "page_id" to h.stringProperty("Optional page_id; defaults to the latest fetched page"),
                "session_id" to h.sessionProperty(),
                required = listOf("section"),
            ),
        ) { request ->
            val args = request.arguments
            h.toolResult {
                h.json.encodeToJsonElement(
                    gateway.expand(
                        sessionId = h.str(args, "session_id") ?: DEFAULT_SESSION_ID,
                        section = h.requiredStr(args, "section"),
                        pageId = h.str(args, "page_id"),
                    ),
                ).jsonObject
            }
        }

        addTool(
            name = "browser_snapshot",
            description = "List visible interactive elements on the current page with stable [eN] IDs.",
            inputSchema = h.schema("session_id" to h.sessionProperty()),
        ) { request ->
            val args = request.arguments
            h.toolResult {
                buildJsonObject {
                    put("elements", buildJsonArray {
                        gateway.snapshot(h.str(args, "session_id") ?: DEFAULT_SESSION_ID).forEach { add(JsonPrimitive(it)) }
                    })
                }
            }
        }

        addTool(
            name = "browser_click",
            description = "Click an interactive element returned by browser_snapshot.",
            inputSchema = h.schema(
                "element_id" to h.stringProperty("Element ID such as e3"),
                "session_id" to h.sessionProperty(),
                required = listOf("element_id"),
            ),
        ) { request ->
            val args = request.arguments
            h.booleanResult {
                gateway.click(h.str(args, "session_id") ?: DEFAULT_SESSION_ID, h.requiredStr(args, "element_id"))
            }
        }

        addTool(
            name = "browser_type",
            description = "Replace the value of an input or textarea.",
            inputSchema = h.schema(
                "element_id" to h.stringProperty("Element ID such as e3"),
                "text" to h.stringProperty("Text to enter"),
                "session_id" to h.sessionProperty(),
                required = listOf("element_id", "text"),
            ),
        ) { request ->
            val args = request.arguments
            h.booleanResult {
                gateway.type(
                    h.str(args, "session_id") ?: DEFAULT_SESSION_ID,
                    h.requiredStr(args, "element_id"),
                    h.requiredStr(args, "text"),
                )
            }
        }

        addTool(
            name = "browser_scroll",
            description = "Scroll the current page by CSS pixels.",
            inputSchema = h.schema(
                "dx" to h.integerProperty("Horizontal delta", default = 0),
                "dy" to h.integerProperty("Vertical delta", default = 600),
                "session_id" to h.sessionProperty(),
            ),
        ) { request ->
            val args = request.arguments
            h.booleanResult {
                gateway.scroll(
                    sessionId = h.str(args, "session_id") ?: DEFAULT_SESSION_ID,
                    dx = h.intArg(args, "dx") ?: 0,
                    dy = h.intArg(args, "dy") ?: 600,
                )
            }
        }

        addTool(
            name = "browser_select",
            description = "Select an option of a <select> dropdown by value or visible text.",
            inputSchema = h.schema(
                "element_id" to h.stringProperty("Element ID such as e3"),
                "value" to h.stringProperty("Option value or visible text"),
                "session_id" to h.sessionProperty(),
                required = listOf("element_id", "value"),
            ),
        ) { request ->
            val args = request.arguments
            h.booleanResult {
                gateway.select(
                    h.str(args, "session_id") ?: DEFAULT_SESSION_ID,
                    h.requiredStr(args, "element_id"),
                    h.requiredStr(args, "value"),
                )
            }
        }

        addTool(
            name = "browser_navigate",
            description = "Navigate the session to a URL and wait for the page to settle (readyState complete + quiet). Returns final URL after redirects.",
            inputSchema = h.schema(
                "url" to h.stringProperty("HTTP(S) URL (https:// auto-prepended for bare hosts)"),
                "session_id" to h.sessionProperty(),
                required = listOf("url"),
            ),
        ) { request ->
            val args = request.arguments
            h.toolResult {
                val url = h.requiredStr(args, "url")
                warnIfLocalTarget(url)
                val (ok, finalUrl) = gateway.navigate(h.str(args, "session_id") ?: DEFAULT_SESSION_ID, url)
                buildJsonObject {
                    put("ok", ok)
                    put("url", finalUrl)
                }
            }
        }

        addTool(
            name = "browser_back",
            description = "Go back one step in the session's history (e.g. after a click triggered navigation) and wait for the page to settle.",
            inputSchema = h.schema("session_id" to h.sessionProperty()),
        ) { request ->
            val args = request.arguments
            h.toolResult {
                val (wentBack, url) = gateway.goBack(h.str(args, "session_id") ?: DEFAULT_SESSION_ID)
                buildJsonObject {
                    put("went_back", wentBack)
                    put("url", url)
                }
            }
        }

        addTool(
            name = "browser_url",
            description = "Return the session's current URL (final address after redirects) and page title.",
            inputSchema = h.schema("session_id" to h.sessionProperty()),
        ) { request ->
            val args = request.arguments
            h.toolResult {
                val sessionId = h.str(args, "session_id") ?: DEFAULT_SESSION_ID
                buildJsonObject {
                    put("url", gateway.currentUrl(sessionId))
                    put("title", gateway.title(sessionId))
                }
            }
        }
    }

    private fun validateRemoteUrl(value: String) {
        val uri = runCatching { URI(value) }.getOrElse { throw IllegalArgumentException("invalid URL") }
        require(uri.scheme == "http" || uri.scheme == "https") { "only http/https URLs are allowed" }
        require(!uri.host.isNullOrBlank()) { "URL host is required" }
    }

    /**
     * browser_navigate 用于 Agent 自主浏览（不限定协议为 http/https），但目标是本机/内网地址时
     * 给出显式提醒而非静默放行——操作日志与调用方都能看到，误操作可被上游策略拦截。
     */
    private fun warnIfLocalTarget(url: String) {
        val host = runCatching { URI(url.trim()).host }.getOrNull() ?: return
        val local = host == "localhost" || host.endsWith(".local") ||
            host == "127.0.0.1" || host.startsWith("127.") ||
            host.startsWith("10.") || host.startsWith("192.168.") || host.startsWith("169.254.") ||
            (host.startsWith("172.") && host.split(".").getOrNull(1)?.toIntOrNull() in 16..31)
        if (local) {
            android.util.Log.w("PageKit", "browser_navigate targets a local/private address: $url")
        }
    }

    private fun validateMode(mode: String, intent: String?) {
        require(mode in setOf("raw", "compact", "focus")) { "unsupported mode: $mode" }
        if (mode == "focus") require(!intent.isNullOrBlank()) { "focus mode requires intent" }
    }
}
