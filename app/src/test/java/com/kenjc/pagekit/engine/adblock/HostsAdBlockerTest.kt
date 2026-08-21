package com.kenjc.pagekit.engine.adblock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostsAdBlockerTest {

    private val source = """
        # comment
        0.0.0.0 ads.example.com
        0.0.0.0 0.0.0.0
        127.0.0.1 tracker.example.net # inline comment
        :: telemetry.example.org
        0.0.0.0 localhost
        192.168.1.1 not-a-block-rule.example
    """.trimIndent()

    @Test
    fun `解析 hosts 并匹配子域名`() {
        val rules = HostsRuleParser.parse(source, "test", loadedAtEpochMs = 1)

        assertEquals(setOf("ads.example.com", "tracker.example.net", "telemetry.example.org"), rules.blockedDomains)
        assertTrue(rules.blocks("cdn.ads.example.com"))
        assertFalse(rules.blocks("example.com"))
        assertFalse(rules.blocks("not-a-block-rule.example"))
        assertFalse(rules.blocks("0.0.0.0"))
    }

    @Test
    fun `主文档与白名单优先放行并记录统计`() {
        val rules = HostsRuleParser.parse(source, "test")
        val blocker = HostsAdBlocker(
            StaticHostsRuleRepository(rules),
            initialAllowlist = setOf("example.net"),
        )

        assertFalse(blocker.shouldBlock("https", "ads.example.com", isForMainFrame = true))
        assertTrue(blocker.shouldBlock("https", "ads.example.com"))
        assertFalse(blocker.shouldBlock("https", "tracker.example.net"))
        assertFalse(blocker.shouldBlock("data", "ads.example.com"))

        assertEquals(AdBlockStats(evaluated = 2, blocked = 1, allowlisted = 1), blocker.stats())
    }

    @Test
    fun `白名单可以原子更新`() {
        val rules = HostsRuleParser.parse(source, "test")
        val blocker = HostsAdBlocker(StaticHostsRuleRepository(rules))
        assertTrue(blocker.shouldBlock("https", "ads.example.com"))
        blocker.updateAllowlist(listOf("example.com"))
        assertFalse(blocker.shouldBlock("https", "ads.example.com"))
    }
}
