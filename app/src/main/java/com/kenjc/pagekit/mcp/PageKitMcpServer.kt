package com.kenjc.pagekit.mcp

import com.kenjc.pagekit.provider.KimiWebSearchAdapter
import com.kenjc.pagekit.provider.SessionWebSearchProvider
import com.kenjc.pagekit.session.DEFAULT_PROFILE_ID
import com.kenjc.pagekit.session.DEFAULT_SESSION_ID
import com.kenjc.pagekit.session.PageKitSessionGateway
import android.util.Log
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.header
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.mcpStreamableHttp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Streamable HTTP server，由前台 Service 持有生命周期。
 *
 * 绑定地址默认 `0.0.0.0`（所有接口：回环、局域网、热点客户端均可访问）。绑定地址本身
 * 不提供安全隔离，访问控制完全依赖 Bearer token + Origin 策略（见 [McpAccessPolicy]）。
 * 如需收窄监听面，可在启动 Intent 传 `--es mcp_bind lan`（仅局域网 IPv4）或
 * `loopback`（仅本机回环），解析规则见 [McpBindMode]。
 *
 * 使用两个独立的 Ktor engine（避免 SSE 插件重复安装）：
 * - port 3000 `/mcp`      — 使用端：webfetch / websearch / expand / browser_*（面向实际使用的 Agent）
 * - port 3000 `/v1/search` — Kimi 原生 SearchWeb 兼容端点
 * - port 3001 `/mcp`      — 管理端：profile_* / session_* / llm_* / proxy_*（面向管理 Agent）
 *
 * 主机侧端口转发：
 *   adb forward tcp:19300 tcp:3000   # 使用端 + WebSearch（仅绑定地址含回环时可用）
 *   adb forward tcp:19301 tcp:3001   # 管理端
 */
class PageKitMcpServerController(
    private val usageTools: PageKitUsageTools,
    private val managementTools: PageKitManagementTools,
    private val kimiWebSearchAdapter: KimiWebSearchAdapter,
    private val sessionGateway: PageKitSessionGateway,
    private val accessPolicy: McpAccessPolicy,
    private val host: String = "0.0.0.0",
    private val usagePort: Int = 3000,
    private val managementPort: Int = 3001,
) {
    private val lifecycleLock = Any()
    private val engines = mutableListOf<EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>>()
    private var startingJob: Job? = null
    private var currentHost: String = host

    /**
     * 启动服务。[bindHost] 为 null 时沿用构造参数 [host]。若服务已在运行且 [bindHost] 与
     * 当前绑定地址不同，先停止旧 engine 再以新地址启动——LAN 模式下 Wi-Fi 重连导致
     * 局域网 IP 变化时也走这条路径自动重绑。
     */
    fun start(scope: CoroutineScope, bindHost: String? = null) {
        val targetHost = bindHost ?: host
        val restartNeeded = synchronized(lifecycleLock) {
            (engines.isNotEmpty() || startingJob?.isActive == true) && currentHost != targetHost
        }
        if (restartNeeded) {
            Log.i(TAG, "MCP bind host changed: $currentHost -> $targetHost, restarting")
            stop()
        }
        synchronized(lifecycleLock) {
            if (engines.isNotEmpty() || startingJob?.isActive == true) return
            startingJob = scope.launch {
                val ownJob = coroutineContext[Job]
                val started = mutableListOf<EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>>()
                try {
                    // ── 使用端 engine (port 3000)：/mcp + /v1/search ──
                    val usageServer: Server = usageTools.createServer()
                    val usageEngine = embeddedServer(CIO, host = targetHost, port = usagePort) {
                        interceptAuth()
                        mcpStreamableHttp(
                            path = "/mcp",
                            enableDnsRebindingProtection = false,
                        ) { usageServer }
                        routing {
                            post("/v1/search") {
                                handleWebSearch(
                                    call = call,
                                    requestedSession = call.request.header(SESSION_HEADER)?.trim()?.takeIf(String::isNotEmpty),
                                    requestedProfile = call.request.header(PROFILE_HEADER)?.trim()?.takeIf(String::isNotEmpty),
                                )
                            }
                            post("/v1/fetch") {
                                handleWebFetch(
                                    call = call,
                                    requestedSession = call.request.header(SESSION_HEADER)?.trim()?.takeIf(String::isNotEmpty),
                                    requestedProfile = call.request.header(PROFILE_HEADER)?.trim()?.takeIf(String::isNotEmpty),
                                )
                            }
                        }
                    }
                    usageEngine.start(wait = false)
                    started.add(usageEngine)

                    // ── 管理端 engine (port 3001)：/mcp ──
                    val managementServer: Server = managementTools.createServer()
                    val managementEngine = embeddedServer(CIO, host = targetHost, port = managementPort) {
                        interceptAuth()
                        mcpStreamableHttp(
                            path = "/mcp",
                            enableDnsRebindingProtection = false,
                        ) { managementServer }
                    }
                    managementEngine.start(wait = false)
                    started.add(managementEngine)

                    val keepRunning = synchronized(lifecycleLock) {
                        if (ownJob?.isActive == true && startingJob === ownJob) {
                            engines.addAll(started)
                            startingJob = null
                            currentHost = targetHost
                            true
                        } else {
                            false
                        }
                    }
                    if (!keepRunning) {
                        started.forEach { it.stop(500, 2_000) }
                    } else {
                        Log.i(TAG, "MCP usage + WebSearch on http://$targetHost:$usagePort, admin on http://$targetHost:$managementPort")
                    }
                } catch (error: Throwable) {
                    synchronized(lifecycleLock) {
                        if (startingJob === ownJob) startingJob = null
                    }
                    started.forEach { it.stop(0, 500) }
                    if (ownJob?.isActive == true) Log.e(TAG, "MCP server failed", error)
                }
            }
        }
    }

    private fun io.ktor.server.application.Application.interceptAuth() {
        intercept(ApplicationCallPipeline.Plugins) {
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
    }

    private suspend fun handleWebSearch(
        call: io.ktor.server.application.ApplicationCall,
        requestedSession: String?,
        requestedProfile: String?,
    ) {
        // 默认使用 UI 绑定的 default session，让用户在屏幕上看到渲染过程；
        // 传了 X-PageKit-Session 则用指定 session，传了 X-PageKit-Profile 但没 session 则建临时 session。
        var ephemeralSession: String? = null
        try {
            val sessionId = when {
                requestedSession != null -> requestedSession
                requestedProfile != null -> sessionGateway.createSession(requestedProfile).sessionId.also { ephemeralSession = it }
                else -> DEFAULT_SESSION_ID
            }
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

    /**
     * kkagent / Kimi Code Web Fetch 协议：
     * POST {base_url}，body {"url": "<目标 URL>"}，Bearer 鉴权。
     * 响应：2xx + 抽取后的正文纯文本（Markdown）。
     */
    private suspend fun handleWebFetch(
        call: io.ktor.server.application.ApplicationCall,
        requestedSession: String?,
        requestedProfile: String?,
    ) {
        // 默认使用 UI 绑定的 default session，让用户在屏幕上看到渲染过程
        var ephemeralSession: String? = null
        try {
            val body = json.decodeFromString<FetchRequestBody>(call.receiveText())
            val url = body.url?.trim()?.takeIf(String::isNotEmpty)
                ?: throw IllegalArgumentException("missing required field: url")
            val uri = runCatching { java.net.URI(url) }.getOrElse { throw IllegalArgumentException("invalid URL") }
            require(uri.scheme == "http" || uri.scheme == "https") { "only http/https URLs are allowed" }
            require(!uri.host.isNullOrBlank()) { "URL host is required" }

            val sessionId = when {
                requestedSession != null -> requestedSession
                requestedProfile != null -> sessionGateway.createSession(requestedProfile).sessionId.also { ephemeralSession = it }
                else -> DEFAULT_SESSION_ID
            }

            val result = sessionGateway.fetch(
                sessionId = sessionId,
                request = com.kenjc.pagekit.api.dto.FetchRequest(url = url, mode = "raw"),
            )
            call.respondText(
                text = result.markdown,
                contentType = ContentType.Text.Plain,
            )
        } catch (error: IllegalArgumentException) {
            call.respondText(
                text = error.message ?: "Invalid request",
                status = HttpStatusCode.BadRequest,
            )
        } catch (error: Throwable) {
            Log.e(TAG, "WebFetch failed", error)
            call.respondText(
                text = error.message ?: "Fetch failed",
                status = HttpStatusCode.InternalServerError,
            )
        } finally {
            ephemeralSession?.let { runCatching { sessionGateway.closeSession(it) } }
        }
    }

    @kotlinx.serialization.Serializable
    private data class FetchRequestBody(val url: String? = null)

    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    fun stop() {
        synchronized(lifecycleLock) {
            startingJob?.cancel()
            startingJob = null
            val running = engines.toList()
            engines.clear()
            running
        }.forEach { it.stop(gracePeriodMillis = 500, timeoutMillis = 2_000) }
    }

    private companion object {
        const val TAG = "PageKit.MCP"
        const val SESSION_HEADER = "X-PageKit-Session"
        const val PROFILE_HEADER = "X-PageKit-Profile"
    }
}
