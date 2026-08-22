package com.kenjc.pagekit.mcp

import java.net.URI
import java.security.MessageDigest

enum class McpAccessDecision {
    ALLOW,
    UNAUTHORIZED,
    FORBIDDEN_ORIGIN,
}

/** localhost MCP 的最小访问策略：随机 Bearer token + 浏览器 Origin 限制。
 *
 * 注意：这只约束“谁能调用本服务”。服务默认绑定 `0.0.0.0`（可用启动参数 `--es mcp_bind`
 * 收窄为 `lan` / `loopback`），任何能到达该地址的客户端都会先经过本策略校验。 */
class McpAccessPolicy(
    private val tokenProvider: () -> String,
) {
    fun evaluate(authorization: String?, origin: String?): McpAccessDecision {
        if (origin != null && !isLoopbackOrigin(origin)) return McpAccessDecision.FORBIDDEN_ORIGIN
        val actual = authorization
            ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
            ?.substringAfter(' ')
            ?.trim()
            ?.toByteArray(Charsets.UTF_8)
            ?: return McpAccessDecision.UNAUTHORIZED
        val expected = tokenProvider().toByteArray(Charsets.UTF_8)
        return if (MessageDigest.isEqual(expected, actual)) {
            McpAccessDecision.ALLOW
        } else {
            McpAccessDecision.UNAUTHORIZED
        }
    }

    private fun isLoopbackOrigin(value: String): Boolean = runCatching {
        val uri = URI(value)
        (uri.scheme == "http" || uri.scheme == "https") &&
            uri.userInfo == null &&
            uri.host in LOOPBACK_HOSTS &&
            uri.path.orEmpty().isEmpty() &&
            uri.query == null &&
            uri.fragment == null
    }.getOrDefault(false)

    private companion object {
        val LOOPBACK_HOSTS = setOf("localhost", "127.0.0.1", "::1", "[::1]")
    }
}
