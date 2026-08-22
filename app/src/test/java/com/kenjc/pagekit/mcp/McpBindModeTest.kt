package com.kenjc.pagekit.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.InetAddress

class McpBindModeTest {

    @Test
    fun `parse accepts known aliases and trims input`() {
        assertNull(McpBindMode.parse(null))
        assertNull(McpBindMode.parse(""))
        assertNull(McpBindMode.parse("   "))
        assertEquals(McpBindMode.ALL, McpBindMode.parse("all"))
        assertEquals(McpBindMode.ALL, McpBindMode.parse("ANY"))
        assertEquals(McpBindMode.ALL, McpBindMode.parse("0.0.0.0"))
        assertEquals(McpBindMode.LAN, McpBindMode.parse("lan"))
        assertEquals(McpBindMode.LAN, McpBindMode.parse(" WiFi "))
        assertEquals(McpBindMode.LOOPBACK, McpBindMode.parse("loopback"))
        assertEquals(McpBindMode.LOOPBACK, McpBindMode.parse("localhost"))
        assertEquals(McpBindMode.LOOPBACK, McpBindMode.parse("127.0.0.1"))
        // 未知值不猜测，交由调用方走默认
        assertNull(McpBindMode.parse("ethernet"))
    }

    @Test
    fun `resolve maps fixed modes to hosts`() {
        assertEquals("0.0.0.0", McpBindHosts.resolve(McpBindMode.ALL))
        assertEquals("127.0.0.1", McpBindHosts.resolve(McpBindMode.LOOPBACK))
    }

    @Test
    fun `selectLanHost prefers site-local ipv4 and skips loopback and ipv6`() {
        val loopback = InetAddress.getByName("127.0.0.1")
        val siteLocal = InetAddress.getByName("192.168.1.42")
        val global = InetAddress.getByName("8.8.8.8")
        val ipv6 = InetAddress.getByName("2001:db8::1")

        assertEquals(
            "192.168.1.42",
            McpBindHosts.selectLanHost(listOf(loopback, ipv6, global, siteLocal)),
        )
        assertEquals(
            "8.8.8.8",
            McpBindHosts.selectLanHost(listOf(loopback, global, ipv6)),
        )
        assertNull(McpBindHosts.selectLanHost(listOf(loopback, ipv6)))
        assertNull(McpBindHosts.selectLanHost(emptyList()))
    }
}
