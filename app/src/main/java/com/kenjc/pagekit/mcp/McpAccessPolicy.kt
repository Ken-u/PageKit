package com.kenjc.pagekit.mcp

import java.net.URI
import java.security.MessageDigest

enum class McpAccessDecision {
    ALLOW,
    UNAUTHORIZED,
    FORBIDDEN_ORIGIN,
}

/** localhost MCP 的最小访问策略：随机 Bearer token + 浏览器 Origin 限制。 */
class McpAccessPolicy(
    expectedToken: String,
) {
    private val expectedTokenBytes = expectedToken.toByteArray(Charsets.UTF_8)

    fun evaluate(authorization: String?, origin: String?): McpAccessDecision {
        if (origin != null && !isLoopbackOrigin(origin)) return McpAccessDecision.FORBIDDEN_ORIGIN
        val actual = authorization
            ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
            ?.substringAfter(' ')
            ?.trim()
            ?.toByteArray(Charsets.UTF_8)
            ?: return McpAccessDecision.UNAUTHORIZED
        return if (MessageDigest.isEqual(expectedTokenBytes, actual)) {
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
