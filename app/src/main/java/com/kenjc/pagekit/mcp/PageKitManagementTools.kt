package com.kenjc.pagekit.mcp

import com.kenjc.pagekit.compress.LlmConfig
import com.kenjc.pagekit.compress.LlmSettings
import com.kenjc.pagekit.net.ProxyConfig
import com.kenjc.pagekit.net.ProxySettings
import com.kenjc.pagekit.net.WebViewProxyApplier
import com.kenjc.pagekit.session.DEFAULT_PROFILE_ID
import com.kenjc.pagekit.session.PageKitSessionGateway
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put

/**
 * 管理端 MCP tools：Profile/Session 管理、LLM 配置、代理配置。
 * 挂载在 `/mcp/admin` 端点，面向管理 Agent。
 */
class PageKitManagementTools(
    private val gateway: PageKitSessionGateway,
    private val llmSettings: LlmSettings,
    private val proxySettings: ProxySettings,
    private val onProxyChanged: (ProxyConfig) -> Unit = WebViewProxyApplier::apply,
) {

    fun createServer(): Server = Server(
        serverInfo = Implementation(name = "pagekit-admin", version = "0.4.0"),
        options = ServerOptions(
            capabilities = ServerCapabilities(
                tools = ServerCapabilities.Tools(listChanged = false),
            ),
        ),
        instructions = "Manage PageKit profiles, sessions, LLM compressor configuration, and WebView proxy settings.",
    ).apply {
        val h = McpToolHelpers

        // ── Profile 管理 ──────────────────────────────────────────

        addTool(
            name = "profile_create",
            description = "Create or open an isolated WebView profile in a dedicated Android process (maximum 3).",
            inputSchema = h.schema(
                "profile_id" to h.stringProperty("Stable profile ID: letters, digits, dot, underscore, dash"),
                required = listOf("profile_id"),
            ),
        ) { request ->
            val args = request.arguments
            h.toolResult {
                buildJsonObject {
                    put("profile", h.json.encodeToJsonElement(gateway.createProfile(h.requiredStr(args, "profile_id"))))
                }
            }
        }

        addTool(
            name = "profile_list",
            description = "List the default profile and all isolated process profiles.",
            inputSchema = h.schema(),
        ) {
            h.toolResult { buildJsonObject { put("profiles", h.json.encodeToJsonElement(gateway.listProfiles())) } }
        }

        addTool(
            name = "profile_delete",
            description = "Close all sessions and permanently clear cookies, storage, cache, and extracted-page cache for an isolated profile.",
            inputSchema = h.schema(
                "profile_id" to h.stringProperty("Isolated profile ID to delete"),
                required = listOf("profile_id"),
            ),
        ) { request ->
            val args = request.arguments
            h.toolResult {
                val deleted = gateway.deleteProfile(h.requiredStr(args, "profile_id"))
                require(deleted) { "profile not found" }
                buildJsonObject { put("deleted", true) }
            }
        }

        // ── Session 管理 ──────────────────────────────────────────

        addTool(
            name = "session_create",
            description = "Create an independent WebView session inside a profile.",
            inputSchema = h.schema(
                "profile_id" to h.stringProperty("Profile ID; defaults to the shared main-process profile"),
            ),
        ) { request ->
            val args = request.arguments
            h.toolResult {
                buildJsonObject {
                    put(
                        "session",
                        h.json.encodeToJsonElement(
                            gateway.createSession(h.str(args, "profile_id") ?: DEFAULT_PROFILE_ID),
                        ),
                    )
                }
            }
        }

        addTool(
            name = "session_list",
            description = "List browser sessions, optionally filtered by profile.",
            inputSchema = h.schema("profile_id" to h.stringProperty("Optional profile ID")),
        ) { request ->
            val args = request.arguments
            h.toolResult {
                buildJsonObject {
                    put("sessions", h.json.encodeToJsonElement(gateway.listSessions(h.str(args, "profile_id"))))
                }
            }
        }

        addTool(
            name = "session_close",
            description = "Destroy a non-default WebView session and release its renderer resources.",
            inputSchema = h.schema(
                "session_id" to h.stringProperty("Session ID returned by session_create"),
                required = listOf("session_id"),
            ),
        ) { request ->
            val args = request.arguments
            h.toolResult {
                val closed = gateway.closeSession(h.requiredStr(args, "session_id"))
                require(closed) { "session not found" }
                buildJsonObject { put("closed", true) }
            }
        }

        addTool(
            name = "session_close_idle",
            description = "Close non-default sessions idle for longer than idle_ms across one or all profiles; frees WebView memory without touching active sessions.",
            inputSchema = h.schema(
                "idle_ms" to h.integerProperty("Idle threshold in milliseconds", default = 600_000),
                "profile_id" to h.stringProperty("Optional profile ID; defaults to all profiles"),
            ),
        ) { request ->
            val args = request.arguments
            h.toolResult {
                buildJsonObject {
                    put("closed", gateway.closeIdleSessions(h.str(args, "profile_id"), (h.intArg(args, "idle_ms") ?: 600_000).toLong().coerceAtLeast(0)))
                }
            }
        }

        // ── LLM 配置 ─────────────────────────────────────────────

        addTool(
            name = "llm_status",
            description = "Show OpenAI-compatible compressor configuration without exposing the API key.",
            inputSchema = h.schema(),
        ) {
            h.toolResult {
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
            inputSchema = h.schema(
                "endpoint" to h.stringProperty("HTTPS API base URL or loopback HTTP URL"),
                "model" to h.stringProperty("Chat completion model name"),
                "api_key" to h.stringProperty("Optional bearer token; omit to keep the current key"),
                "clear_api_key" to h.booleanProperty("Clear the stored API key", default = false),
                required = listOf("endpoint", "model"),
            ),
        ) { request ->
            val args = request.arguments
            h.toolResult {
                val previous = llmSettings.load()
                val key = when {
                    h.boolArg(args, "clear_api_key") == true -> ""
                    args?.containsKey("api_key") == true -> h.rawStr(args, "api_key")
                    else -> previous.apiKey
                }
                llmSettings.save(
                    LlmConfig(
                        endpoint = h.requiredStr(args, "endpoint"),
                        model = h.requiredStr(args, "model"),
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

        // ── 代理配置 ──────────────────────────────────────────────

        addTool(
            name = "proxy_status",
            description = "Show the current WebView proxy configuration.",
            inputSchema = h.schema(),
        ) {
            h.toolResult {
                val config = proxySettings.load()
                buildJsonObject {
                    put("enabled", config.enabled)
                    put("active", config.active)
                    put("host", config.host)
                    put("port", config.port)
                    put("bypass", config.bypass)
                }
            }
        }

        addTool(
            name = "proxy_configure",
            description = "Configure the WebView proxy. Only affects WebView network, not the system proxy. Requires app restart or new profile to take effect in worker processes.",
            inputSchema = h.schema(
                "enabled" to h.booleanProperty("Enable or disable the proxy", default = false),
                "host" to h.stringProperty("Proxy server host (e.g. 127.0.0.1)"),
                "port" to h.integerProperty("Proxy server port (1-65535)", default = 0),
                "bypass" to h.stringProperty("Semicolon-separated bypass rules (e.g. localhost;10.0.0.0/8;*.local)"),
                required = listOf("enabled"),
            ),
        ) { request ->
            val args = request.arguments
            h.toolResult {
                val previous = proxySettings.load()
                val enabled = h.boolArg(args, "enabled") ?: false
                val host = h.str(args, "host") ?: previous.host
                val port = h.intArg(args, "port") ?: previous.port
                val bypass = h.str(args, "bypass") ?: previous.bypass
                if (enabled) {
                    require(host.isNotBlank()) { "host is required when proxy is enabled" }
                    require(port in 1..65535) { "port must be 1-65535 when proxy is enabled" }
                }
                val config = ProxyConfig(
                    enabled = enabled,
                    host = host,
                    port = port,
                    bypass = bypass,
                )
                proxySettings.save(config)
                // 立即应用到当前进程的 WebView（主进程）
                onProxyChanged(config)
                buildJsonObject {
                    put("enabled", config.enabled)
                    put("active", config.active)
                    put("host", config.host)
                    put("port", config.port)
                    put("bypass", config.bypass)
                }
            }
        }
    }
}
