package com.kenjc.pagekit.mcp

import org.junit.Assert.assertEquals
import org.junit.Test

class McpAccessPolicyTest {
    private val policy = McpAccessPolicy("correct-secret-token")

    @Test
    fun `缺失或错误 token 被拒绝`() {
        assertEquals(McpAccessDecision.UNAUTHORIZED, policy.evaluate(null, null))
        assertEquals(McpAccessDecision.UNAUTHORIZED, policy.evaluate("Bearer wrong", null))
        assertEquals(McpAccessDecision.UNAUTHORIZED, policy.evaluate("Basic correct-secret-token", null))
    }

    @Test
    fun `正确 token 可从原生客户端访问`() {
        assertEquals(McpAccessDecision.ALLOW, policy.evaluate("Bearer correct-secret-token", null))
        assertEquals(McpAccessDecision.ALLOW, policy.evaluate("bearer correct-secret-token", null))
    }

    @Test
    fun `浏览器只允许回环 Origin`() {
        assertEquals(
            McpAccessDecision.ALLOW,
            policy.evaluate("Bearer correct-secret-token", "http://localhost:6274"),
        )
        assertEquals(
            McpAccessDecision.ALLOW,
            policy.evaluate("Bearer correct-secret-token", "https://[::1]:6274"),
        )
        assertEquals(
            McpAccessDecision.FORBIDDEN_ORIGIN,
            policy.evaluate("Bearer correct-secret-token", "https://attacker.example"),
        )
        assertEquals(
            McpAccessDecision.FORBIDDEN_ORIGIN,
            policy.evaluate("Bearer correct-secret-token", "null"),
        )
    }
}
