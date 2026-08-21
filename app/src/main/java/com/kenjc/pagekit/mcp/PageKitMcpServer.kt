package com.kenjc.pagekit.mcp

import com.kenjc.pagekit.api.dto.FetchRequest
import com.kenjc.pagekit.compress.LlmConfig
import com.kenjc.pagekit.compress.LlmSettings
import com.kenjc.pagekit.provider.KimiWebSearchAdapter
import com.kenjc.pagekit.provider.SessionWebSearchProvider
import com.kenjc.pagekit.provider.WebSearchRequest
import com.kenjc.pagekit.session.DEFAULT_PROFILE_ID
import com.kenjc.pagekit.session.DEFAULT_SESSION_ID
import com.kenjc.pagekit.session.PageKitSessionGateway
import android.util.Log
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.header
import io.ktor.server.request.path
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
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
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** Session/Profile gateway 到 MCP tools 的协议适配层。 */
class PageKitMcpTools(
    private val gateway: PageKitSessionGateway,
    private val llmSettings: LlmSettings,
) {
    private val json = Json { encodeDefaults = false }

    fun createServer(): Server = Server(
        serverInfo = Implementation(name = "pagekit", version = "0.4.0"),
        options = ServerOptions(
            capabilities = ServerCapabilities(
                tools = ServerCapabilities.Tools(listChanged = false),
            ),
        ),
        instructions = "Create isolated browser sessions and multi-process profiles, render pages in Android WebView, remove ads/noise, and extract structured content.",
    ).apply {
        addTool(
            name = "profile_create",
            description = "Create or open an isolated WebView profile in a dedicated Android process (maximum 3).",
            inputSchema = schema(
                "profile_id" to stringProperty("Stable profile ID: letters, digits, dot, underscore, dash"),
                required = listOf("profile_id"),
            ),
        ) { request ->
            toolResult {
                buildJsonObject {
                    put("profile", json.encodeToJsonElement(gateway.createProfile(request.arguments.requiredString("profile_id"))))
                }
            }
        }

        addTool(
            name = "profile_list",
            description = "List the default profile and all isolated process profiles.",
            inputSchema = schema(),
        ) {
            toolResult { buildJsonObject { put("profiles", json.encodeToJsonElement(gateway.listProfiles())) } }
        }

        addTool(
            name = "profile_delete",
            description = "Close all sessions and permanently clear cookies, storage, cache, and extracted-page cache for an isolated profile.",
            inputSchema = schema(
                "profile_id" to stringProperty("Isolated profile ID to delete"),
                required = listOf("profile_id"),
            ),
        ) { request ->
            toolResult {
                val deleted = gateway.deleteProfile(request.arguments.requiredString("profile_id"))
                require(deleted) { "profile not found" }
                buildJsonObject { put("deleted", true) }
            }
        }

        addTool(
            name = "session_create",
            description = "Create an independent WebView session inside a profile.",
            inputSchema = schema(
                "profile_id" to stringProperty("Profile ID; defaults to the shared main-process profile"),
            ),
        ) { request ->
            toolResult {
                buildJsonObject {
                    put(
                        "session",
                        json.encodeToJsonElement(
                            gateway.createSession(request.arguments.string("profile_id") ?: DEFAULT_PROFILE_ID),
                        ),
                    )
                }
            }
        }

        addTool(
            name = "session_list",
            description = "List browser sessions, optionally filtered by profile.",
            inputSchema = schema("profile_id" to stringProperty("Optional profile ID")),
        ) { request ->
            toolResult {
                buildJsonObject {
                    put("sessions", json.encodeToJsonElement(gateway.listSessions(request.arguments.string("profile_id"))))
                }
            }
        }

        addTool(
            name = "session_close",
            description = "Destroy a non-default WebView session and release its renderer resources.",
            inputSchema = schema(
                "session_id" to stringProperty("Session ID returned by session_create"),
                required = listOf("session_id"),
            ),
        ) { request ->
            toolResult {
                val closed = gateway.closeSession(request.arguments.requiredString("session_id"))
                require(closed) { "session not found" }
                buildJsonObject { put("closed", true) }
            }
        }

        addTool(
            name = "webfetch",
            description = "Render and extract an HTTP(S) page in raw, compact, or intent-focused mode.",
            inputSchema = schema(
                "url" to stringProperty("HTTP(S) URL"),
                "intent" to stringProperty("Optional extraction intent"),
                "mode" to stringProperty("Extraction mode", enum = listOf("raw", "compact", "focus")),
                "session_id" to sessionProperty(),
                required = listOf("url"),
            ),
        ) { request ->
            toolResult {
                val url = request.arguments.requiredString("url")
                validateRemoteUrl(url)
                val mode = request.arguments.string("mode") ?: "raw"
                validateMode(mode, request.arguments.string("intent"))
                val result = gateway.fetch(
                    sessionId = request.arguments.sessionId(),
                    request = FetchRequest(
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
                    put("compression_mode", mode)
                    put("duration_ms", result.durationMs)
                }
            }
        }

        addTool(
            name = "websearch",
            description = "Search the web and return structured results; optionally render each result and include its Markdown content.",
            inputSchema = schema(
                "query" to stringProperty("Search query"),
                "engine" to stringProperty("Search engine", enum = listOf("bing", "baidu", "sogou", "360", "google")),
                "limit" to integerProperty("Number of results (1-20)", default = 5),
                "include_content" to booleanProperty("Render result pages and include Markdown content", default = false),
                "session_id" to sessionProperty(),
                required = listOf("query"),
            ),
        ) { request ->
            toolResult {
                val startedAt = System.currentTimeMillis()
                val result = SessionWebSearchProvider(gateway, request.arguments.sessionId()).search(
                    WebSearchRequest(
                        query = request.arguments.requiredString("query"),
                        engine = request.arguments.string("engine") ?: "bing",
                        limit = request.arguments.int("limit") ?: 5,
                        includeContent = request.arguments.boolean("include_content") ?: false,
                    ),
                )
                buildJsonObject {
                    put("query", result.query)
                    put("results", json.encodeToJsonElement(result.results))
                    put("duration_ms", System.currentTimeMillis() - startedAt)
                }
            }
        }

        addTool(
            name = "expand",
            description = "Return one original Markdown section from the page cache without fetching the page again.",
            inputSchema = schema(
                "section" to stringProperty("Section ID (such as s2) or heading"),
                "page_id" to stringProperty("Optional page_id; defaults to the latest fetched page"),
                "session_id" to sessionProperty(),
                required = listOf("section"),
            ),
        ) { request ->
            toolResult {
                json.encodeToJsonElement(
                    gateway.expand(
                        sessionId = request.arguments.sessionId(),
                        section = request.arguments.requiredString("section"),
                        pageId = request.arguments.string("page_id"),
                    ),
                ).jsonObject
            }
        }

        addTool(
            name = "browser_snapshot",
            description = "List visible interactive elements on the current page with stable [eN] IDs.",
            inputSchema = schema("session_id" to sessionProperty()),
        ) { request ->
            toolResult {
                buildJsonObject {
                    put("elements", buildJsonArray {
                        gateway.snapshot(request.arguments.sessionId()).forEach { add(JsonPrimitive(it)) }
                    })
                }
            }
        }

        addTool(
            name = "browser_click",
            description = "Click an interactive element returned by browser_snapshot.",
            inputSchema = schema(
                "element_id" to stringProperty("Element ID such as e3"),
                "session_id" to sessionProperty(),
                required = listOf("element_id"),
            ),
        ) { request ->
            booleanResult {
                gateway.click(request.arguments.sessionId(), request.arguments.requiredString("element_id"))
            }
        }

        addTool(
            name = "browser_type",
            description = "Replace the value of an input or textarea.",
            inputSchema = schema(
                "element_id" to stringProperty("Element ID such as e3"),
                "text" to stringProperty("Text to enter"),
                "session_id" to sessionProperty(),
                required = listOf("element_id", "text"),
            ),
        ) { request ->
            booleanResult {
                gateway.type(
                    request.arguments.sessionId(),
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
                "session_id" to sessionProperty(),
            ),
        ) { request ->
            booleanResult {
                gateway.scroll(
                    sessionId = request.arguments.sessionId(),
                    dx = request.arguments.int("dx") ?: 0,
                    dy = request.arguments.int("dy") ?: 600,
                )
            }
        }

        addTool(
            name = "llm_status",
            description = "Show OpenAI-compatible compressor configuration without exposing the API key.",
            inputSchema = schema(),
        ) {
            toolResult {
                val status = llmSettings.status()
                buildJsonObject {
                    put("endpoint", status.endpoint)
                    put("model", status.model)
                    put("configured", status.configured)
                    put("has_api_key", status.hasApiKey)
                }
            }
        }

        addTool(
            name = "llm_configure",
            description = "Configure the OpenAI-compatible compressor. API keys are stored in app-private preferences and never returned.",
            inputSchema = schema(
                "endpoint" to stringProperty("HTTPS API base URL or loopback HTTP URL"),
                "model" to stringProperty("Chat completion model name"),
                "api_key" to stringProperty("Optional bearer token; omit to keep the current key"),
                "clear_api_key" to booleanProperty("Clear the stored API key", default = false),
                required = listOf("endpoint", "model"),
            ),
        ) { request ->
            toolResult {
                val previous = llmSettings.load()
                val key = when {
                    request.arguments.boolean("clear_api_key") == true -> ""
                    request.arguments?.containsKey("api_key") == true -> request.arguments.rawString("api_key")
                    else -> previous.apiKey
                }
                llmSettings.save(
                    LlmConfig(
                        endpoint = request.arguments.requiredString("endpoint"),
                        model = request.arguments.requiredString("model"),
                        apiKey = key,
                        maxInputChars = previous.maxInputChars,
                        maxOutputTokens = previous.maxOutputTokens,
                    ),
                )
                val status = llmSettings.status()
                buildJsonObject {
                    put("configured", status.configured)
                    put("endpoint", status.endpoint)
                    put("model", status.model)
                    put("has_api_key", status.hasApiKey)
                }
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

    private fun validateMode(mode: String, intent: String?) {
        require(mode in setOf("raw", "compact", "focus")) { "unsupported mode: $mode" }
        if (mode == "focus") require(!intent.isNullOrBlank()) { "focus mode requires intent" }
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

    private fun booleanProperty(description: String, default: Boolean) = buildJsonObject {
        put("type", "boolean")
        put("description", description)
        put("default", default)
    }

    private fun sessionProperty() = stringProperty("Browser session ID; defaults to the visible UI session")

    private fun JsonObject?.string(name: String): String? {
        val value = this?.get(name)?.jsonPrimitive?.content?.trim()?.takeIf(String::isNotEmpty)
        return value
    }

    private fun JsonObject?.requiredString(name: String): String =
        requireNotNull(string(name)) { "missing required argument: $name" }

    private fun JsonObject?.rawString(name: String): String =
        runCatching { this?.get(name)?.jsonPrimitive?.content }.getOrNull().orEmpty()

    private fun JsonObject?.int(name: String): Int? = this?.get(name)?.jsonPrimitive?.intOrNull
    private fun JsonObject?.boolean(name: String): Boolean? = this?.get(name)?.jsonPrimitive?.booleanOrNull
    private fun JsonObject?.sessionId(): String = string("session_id") ?: DEFAULT_SESSION_ID
}

/** 仅绑定设备回环地址的 Streamable HTTP server，由前台 Service 持有生命周期。 */
class PageKitMcpServerController(
    private val tools: PageKitMcpTools,
    private val kimiWebSearchAdapter: KimiWebSearchAdapter,
    private val sessionGateway: PageKitSessionGateway,
    private val accessPolicy: McpAccessPolicy,
    private val host: String = "127.0.0.1",
    private val port: Int = 3000,
) {
    private val lifecycleLock = Any()
    private var engine: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
    private var startingJob: Job? = null

    fun start(scope: CoroutineScope) {
        synchronized(lifecycleLock) {
            if (engine != null || startingJob?.isActive == true) return
            startingJob = scope.launch {
                val ownJob = coroutineContext[Job]
                var newEngine: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
                try {
                    val mcpServer = tools.createServer()
                    newEngine = embeddedServer(CIO, host = host, port = port) {
                        intercept(ApplicationCallPipeline.Plugins) {
                            if (context.request.path() !in PROTECTED_PATHS) return@intercept
                            when (
                                accessPolicy.evaluate(
                                    authorization = context.request.header("Authorization"),
                                    origin = context.request.header("Origin"),
                                )
                            ) {
                                McpAccessDecision.ALLOW -> Unit
                                McpAccessDecision.UNAUTHORIZED -> {
                                    context.response.headers.append("WWW-Authenticate", "Bearer")
                                    context.respondText("Unauthorized", status = HttpStatusCode.Unauthorized)
                                    finish()
                                }
                                McpAccessDecision.FORBIDDEN_ORIGIN -> {
                                    context.respondText("Forbidden Origin", status = HttpStatusCode.Forbidden)
                                    finish()
                                }
                            }
                        }
                        mcpStreamableHttp(path = "/mcp") { mcpServer }
                        routing {
                            post("/v1/search") {
                                val requestedSession = call.request.header(SESSION_HEADER)?.trim()?.takeIf(String::isNotEmpty)
                                var ephemeralSession: String? = null
                                try {
                                    val sessionId = requestedSession ?: sessionGateway.createSession(
                                        call.request.header(PROFILE_HEADER)?.trim()?.takeIf(String::isNotEmpty)
                                            ?: DEFAULT_PROFILE_ID,
                                    ).sessionId.also { ephemeralSession = it }
                                    call.respondText(
                                        text = kimiWebSearchAdapter.handle(
                                            requestBody = call.receiveText(),
                                            scopedProvider = SessionWebSearchProvider(sessionGateway, sessionId),
                                        ),
                                        contentType = ContentType.Application.Json,
                                    )
                                } catch (error: IllegalArgumentException) {
                                    call.respondText(
                                        text = error.message ?: "Invalid request",
                                        status = HttpStatusCode.BadRequest,
                                    )
                                } catch (error: Throwable) {
                                    Log.e(TAG, "WebSearch provider failed", error)
                                    call.respondText(
                                        text = error.message ?: "Search failed",
                                        status = HttpStatusCode.InternalServerError,
                                    )
                                } finally {
                                    ephemeralSession?.let { runCatching { sessionGateway.closeSession(it) } }
                                }
                            }
                        }
                    }
                    newEngine.start(wait = false)
                    val keepRunning = synchronized(lifecycleLock) {
                        if (ownJob?.isActive == true && startingJob === ownJob) {
                            engine = newEngine
                            startingJob = null
                            true
                        } else {
                            false
                        }
                    }
                    if (!keepRunning) newEngine.stop(500, 2_000)
                    else Log.i(TAG, "MCP + WebSearch listening on http://$host:$port")
                } catch (error: Throwable) {
                    synchronized(lifecycleLock) {
                        if (startingJob === ownJob) startingJob = null
                    }
                    newEngine?.stop(0, 500)
                    if (ownJob?.isActive == true) Log.e(TAG, "MCP server failed", error)
                }
            }
        }
    }

    fun stop() {
        val running = synchronized(lifecycleLock) {
            startingJob?.cancel()
            startingJob = null
            engine.also { engine = null }
        }
        running?.stop(gracePeriodMillis = 500, timeoutMillis = 2_000)
    }

    private companion object {
        const val TAG = "PageKit.MCP"
        val PROTECTED_PATHS = setOf("/mcp", "/v1/search")
        const val SESSION_HEADER = "X-PageKit-Session"
        const val PROFILE_HEADER = "X-PageKit-Profile"
    }
}
