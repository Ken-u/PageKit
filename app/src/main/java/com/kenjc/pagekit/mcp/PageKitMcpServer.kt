package com.kenjc.pagekit.mcp

import com.kenjc.pagekit.api.DefaultPageKitApi
import com.kenjc.pagekit.api.dto.FetchRequest
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.mcpStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import java.net.URI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** PageKitApi 到 MCP tools 的协议薄适配层。 */
class PageKitMcpTools(
    private val api: DefaultPageKitApi,
) {
    private val json = Json { encodeDefaults = false }

    fun createServer(): Server = Server(
        serverInfo = Implementation(name = "pagekit", version = "0.2.0"),
        options = ServerOptions(
            capabilities = ServerCapabilities(
                tools = ServerCapabilities.Tools(listChanged = false),
            ),
        ),
        instructions = "Render web pages in Android WebView, remove ads/noise, and return Markdown plus structured data.",
    ).apply {
        addTool(
            name = "webfetch",
            description = "Render and extract an HTTP(S) page. V2 currently supports raw mode.",
            inputSchema = schema(
                "url" to stringProperty("HTTP(S) URL"),
                "intent" to stringProperty("Optional extraction intent"),
                "mode" to stringProperty("Extraction mode", enum = listOf("raw")),
                required = listOf("url"),
            ),
        ) { request ->
            toolResult {
                val url = request.arguments.requiredString("url")
                validateRemoteUrl(url)
                val mode = request.arguments.string("mode") ?: "raw"
                require(mode == "raw") { "unsupported mode: $mode (only raw is available)" }
                val result = api.fetchDetailed(
                    FetchRequest(
                        url = url,
                        intent = request.arguments.string("intent"),
                        mode = mode,
                    ),
                )
                buildJsonObject {
                    put("page", json.encodeToJsonElement(result.page))
                    put("markdown", result.markdown)
                    put("byline", result.byline)
                    put("extraction_mode", result.extractionMode)
                    put("duration_ms", result.durationMs)
                }
            }
        }

        addTool(
            name = "websearch",
            description = "Open a search result page in WebView and return its extracted raw content.",
            inputSchema = schema(
                "query" to stringProperty("Search query"),
                "engine" to stringProperty("Search engine", enum = listOf("bing", "baidu", "sogou", "360", "google")),
                required = listOf("query"),
            ),
        ) { request ->
            toolResult {
                val result = api.webSearchDetailed(
                    query = request.arguments.requiredString("query"),
                    engine = request.arguments.string("engine") ?: "bing",
                )
                buildJsonObject {
                    put("page", json.encodeToJsonElement(result.page))
                    put("markdown", result.markdown)
                    put("extraction_mode", result.extractionMode)
                    put("duration_ms", result.durationMs)
                }
            }
        }

        addTool(
            name = "browser_snapshot",
            description = "List visible interactive elements on the current page with stable [eN] IDs.",
            inputSchema = schema(),
        ) {
            toolResult {
                buildJsonObject {
                    put("elements", buildJsonArray { api.listInteractiveElements().forEach { add(JsonPrimitive(it)) } })
                }
            }
        }

        addTool(
            name = "browser_click",
            description = "Click an interactive element returned by browser_snapshot.",
            inputSchema = schema(
                "element_id" to stringProperty("Element ID such as e3"),
                required = listOf("element_id"),
            ),
        ) { request ->
            booleanResult { api.click(request.arguments.requiredString("element_id")) }
        }

        addTool(
            name = "browser_type",
            description = "Replace the value of an input or textarea.",
            inputSchema = schema(
                "element_id" to stringProperty("Element ID such as e3"),
                "text" to stringProperty("Text to enter"),
                required = listOf("element_id", "text"),
            ),
        ) { request ->
            booleanResult {
                api.type(
                    request.arguments.requiredString("element_id"),
                    request.arguments.requiredString("text"),
                )
            }
        }

        addTool(
            name = "browser_scroll",
            description = "Scroll the current page by CSS pixels.",
            inputSchema = schema(
                "dx" to integerProperty("Horizontal delta", default = 0),
                "dy" to integerProperty("Vertical delta", default = 600),
            ),
        ) { request ->
            booleanResult {
                api.scroll(
                    dx = request.arguments.int("dx") ?: 0,
                    dy = request.arguments.int("dy") ?: 600,
                )
            }
        }
    }

    private suspend fun toolResult(block: suspend () -> JsonObject): CallToolResult = try {
        val structured = block()
        CallToolResult(
            content = listOf(TextContent(text = json.encodeToString(structured))),
            isError = false,
            structuredContent = structured,
        )
    } catch (error: Throwable) {
        CallToolResult(
            content = listOf(TextContent(text = error.message ?: error::class.java.simpleName)),
            isError = true,
        )
    }

    private suspend fun booleanResult(block: suspend () -> Boolean): CallToolResult = toolResult {
        val ok = block()
        require(ok) { "browser operation failed; take a new browser_snapshot and retry" }
        buildJsonObject { put("ok", true) }
    }

    private fun validateRemoteUrl(value: String) {
        val uri = runCatching { URI(value) }.getOrElse { throw IllegalArgumentException("invalid URL") }
        require(uri.scheme == "http" || uri.scheme == "https") { "only http/https URLs are allowed" }
        require(!uri.host.isNullOrBlank()) { "URL host is required" }
    }

    private fun schema(
        vararg properties: Pair<String, JsonObject>,
        required: List<String> = emptyList(),
    ) = ToolSchema(
        properties = buildJsonObject { properties.forEach { (name, value) -> put(name, value) } },
        required = required,
    )

    private fun stringProperty(description: String, enum: List<String> = emptyList()) = buildJsonObject {
        put("type", "string")
        put("description", description)
        if (enum.isNotEmpty()) put("enum", JsonArray(enum.map(::JsonPrimitive)))
    }

    private fun integerProperty(description: String, default: Int) = buildJsonObject {
        put("type", "integer")
        put("description", description)
        put("default", default)
    }

    private fun JsonObject?.string(name: String): String? {
        val value = this?.get(name)?.jsonPrimitive?.content?.trim()?.takeIf(String::isNotEmpty)
        return value
    }

    private fun JsonObject?.requiredString(name: String): String =
        requireNotNull(string(name)) { "missing required argument: $name" }

    private fun JsonObject?.int(name: String): Int? = this?.get(name)?.jsonPrimitive?.intOrNull
}

/** 进程内 Streamable HTTP server；持久前台生命周期与认证在下一阶段补齐。 */
class PageKitMcpServerController(
    private val tools: PageKitMcpTools,
    private val host: String = "127.0.0.1",
    private val port: Int = 3000,
) {
    @Volatile
    private var engine: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

    fun start(scope: CoroutineScope) {
        if (engine != null) return
        scope.launch {
            val mcpServer = tools.createServer()
            val newEngine = embeddedServer(CIO, host = host, port = port) {
                mcpStreamableHttp(path = "/mcp") { mcpServer }
            }
            newEngine.start(wait = false)
            engine = newEngine
        }
    }

    fun stop() {
        engine?.stop(gracePeriodMillis = 500, timeoutMillis = 2_000)
        engine = null
    }
}
