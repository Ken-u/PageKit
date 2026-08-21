package com.kenjc.pagekit.engine.adblock

import android.util.Log
import android.webkit.WebResourceRequest
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

data class AdBlockStats(
    val evaluated: Long,
    val blocked: Long,
    val allowlisted: Long,
)

/** StevenBlack hosts 请求级过滤器；白名单优先，且永不拦截主文档。 */
class HostsAdBlocker(
    private val repository: HostsRuleRepository,
    initialAllowlist: Set<String> = emptySet(),
) : AdBlocker {

    private val allowlist = AtomicReference(normalizeAllowlist(initialAllowlist))
    private val evaluated = AtomicLong()
    private val blocked = AtomicLong()
    private val allowlisted = AtomicLong()

    override fun shouldBlock(request: WebResourceRequest): Boolean = shouldBlock(
        scheme = request.url.scheme,
        host = request.url.host,
        isForMainFrame = request.isForMainFrame,
    )

    fun shouldBlock(scheme: String?, host: String?, isForMainFrame: Boolean = false): Boolean {
        if (isForMainFrame || (scheme != "http" && scheme != "https")) return false
        val normalizedHost = normalizeHost(host ?: return false)
        if (normalizedHost.isEmpty()) return false
        evaluated.incrementAndGet()
        if (allowlist.get().matchesHostOrParent(normalizedHost)) {
            allowlisted.incrementAndGet()
            return false
        }
        if (!repository.snapshot().blocks(normalizedHost)) return false

        val count = blocked.incrementAndGet()
        if (count <= 10L || count % 100L == 0L) {
            // android.jar 的 Log 在本地 JVM 测试中没有实现；日志不能影响过滤决策。
            runCatching { Log.i(TAG, "blocked host=$normalizedHost total=$count") }
        }
        return true
    }

    fun updateAllowlist(domains: Collection<String>) {
        allowlist.set(normalizeAllowlist(domains))
    }

    fun stats(): AdBlockStats = AdBlockStats(
        evaluated = evaluated.get(),
        blocked = blocked.get(),
        allowlisted = allowlisted.get(),
    )

    private fun normalizeAllowlist(domains: Collection<String>): Set<String> =
        domains.map(::normalizeHost).filter(String::isNotEmpty).toSet()

    companion object {
        private const val TAG = "PageKit.AdBlock"
    }
}
