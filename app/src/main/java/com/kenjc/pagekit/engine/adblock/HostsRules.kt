package com.kenjc.pagekit.engine.adblock

import java.net.IDN
import java.util.Locale

/** hosts 规则的不可变内存快照，可安全地被 WebView IO 线程并发读取。 */
data class HostsRuleSnapshot(
    val blockedDomains: Set<String>,
    val sourceVersion: String,
    val loadedAtEpochMs: Long,
) {
    fun blocks(host: String): Boolean = blockedDomains.matchesHostOrParent(normalizeHost(host))

    companion object {
        val EMPTY = HostsRuleSnapshot(emptySet(), "empty", 0L)
    }
}

/** StevenBlack/标准 hosts 文本解析器。 */
object HostsRuleParser {

    private val ignoredHosts = setOf(
        "localhost",
        "localhost.localdomain",
        "local",
        "broadcasthost",
        "ip6-localhost",
        "ip6-loopback",
        "ip6-allnodes",
        "ip6-allrouters",
    )

    fun parse(text: String, sourceVersion: String, loadedAtEpochMs: Long = System.currentTimeMillis()): HostsRuleSnapshot {
        val domains = LinkedHashSet<String>()
        text.lineSequence().forEach { rawLine ->
            val line = rawLine.substringBefore('#').trim()
            if (line.isEmpty()) return@forEach
            val fields = line.split(Regex("\\s+")).filter(String::isNotBlank)
            if (fields.size < 2 || !fields.first().isBlockingAddress()) return@forEach
            fields.drop(1).forEach { candidate ->
                val host = normalizeHost(candidate)
                if (
                    host.isNotEmpty() &&
                    host !in ignoredHosts &&
                    !host.isBlockingAddress() &&
                    host.contains('.')
                ) {
                    domains += host
                }
            }
        }
        return HostsRuleSnapshot(domains, sourceVersion, loadedAtEpochMs)
    }

    private fun String.isBlockingAddress(): Boolean = this == "0.0.0.0" || this == "127.0.0.1" || this == "::"
}

internal fun normalizeHost(value: String): String = runCatching {
    IDN.toASCII(value.trim().trimEnd('.')).lowercase(Locale.ROOT)
}.getOrDefault("")

internal fun Set<String>.matchesHostOrParent(host: String): Boolean {
    if (host.isEmpty()) return false
    var candidate = host
    while (true) {
        if (candidate in this) return true
        val dot = candidate.indexOf('.')
        if (dot < 0) return false
        candidate = candidate.substring(dot + 1)
    }
}
