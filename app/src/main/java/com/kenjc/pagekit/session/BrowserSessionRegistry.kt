package com.kenjc.pagekit.session

import android.webkit.WebView
import com.kenjc.pagekit.api.DefaultPageKitApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class BrowserSessionComponents(
    val api: DefaultPageKitApi,
    val destroy: suspend () -> Unit,
    /** 该 session 的 WebView，供 UI 上屏跟随（headless 进程里也始终存在）。 */
    val webView: android.webkit.WebView? = null,
)

fun interface BrowserSessionFactory {
    suspend fun create(sessionId: String): BrowserSessionComponents
}

/** 一个 Profile 进程内的多 WebView Session 注册表。 */
class BrowserSessionRegistry(
    private val profileId: String,
    private val processSlot: Int,
    private val factory: BrowserSessionFactory,
    defaultSession: BrowserSessionComponents? = null,
    initialMaxSessions: Int = 4,
) {
    private data class Entry(
        val components: BrowserSessionComponents,
        val createdAtEpochMs: Long,
        var lastAccessEpochMs: Long,
        val isDefault: Boolean,
        val operationMutex: Mutex = Mutex(),
    )

    private val registryMutex = Mutex()
    private val entries = linkedMapOf<String, Entry>()
    private val defaultFactory: (suspend () -> BrowserSessionComponents)? =
        defaultSession?.let { { it } }

    /** 每 profile 最大并发 session 数（含 default）；可在运行期调整，缩小后逐步淘汰超额空闲 session。 */
    @Volatile
    var maxSessions: Int = initialMaxSessions
        set(value) {
            require(value in 1..8) { "maxSessions must be between 1 and 8" }
            field = value
        }

    /** UI 跟随的活跃 WebView（正在执行操作的 session）；null = 空闲，UI 显示占位。 */
    private val _activeWebView = MutableStateFlow<WebView?>(null)
    val activeWebView: StateFlow<WebView?> = _activeWebView.asStateFlow()

    /** 最近一次活跃的 session id，供 UI 标注当前上屏的是谁。 */
    private val _activeSessionId = MutableStateFlow<String?>(null)
    val activeSessionId: StateFlow<String?> = _activeSessionId.asStateFlow()

    init {
        require(PROFILE_ID.matches(profileId)) { "invalid profile_id" }
        require(processSlot in 0..3) { "invalid process slot" }
        if (defaultSession != null) {
            val now = System.currentTimeMillis()
            entries[DEFAULT_SESSION_ID] = Entry(defaultSession, now, now, isDefault = true)
        }
    }

    suspend fun create(sessionId: String, evictIdleMs: Long? = null): SessionInfo {
        require(SESSION_ID.matches(sessionId)) { "invalid session_id" }
        // 两段式：先（必要时）淘汰空闲 session 并 destroy（锁外），再插入新 session。
        registryMutex.withLock {
            require(sessionId !in entries) { "session already exists: $sessionId" }
            if (entries.size >= maxSessions) {
                val victim = if (evictIdleMs != null) {
                    val cutoff = System.currentTimeMillis() - evictIdleMs
                    entries.entries
                        .filter { !it.value.isDefault && it.value.lastAccessEpochMs <= cutoff }
                        .minByOrNull { it.value.lastAccessEpochMs }
                } else {
                    null
                }
                if (victim == null) {
                    throw IllegalArgumentException(
                        "profile $profileId reached the $maxSessions session limit; close an idle session first",
                    )
                }
                check(entries.remove(victim.key, victim.value)) { "concurrent session eviction" }
                victim.value
            } else {
                null
            }
        }?.let { evicted ->
            // 锁外销毁；若销毁失败仅记录，不影响新 session 创建。
            evicted.operationMutex.withLock { runCatching { evicted.components.destroy() } }
        }

        val entry = registryMutex.withLock {
            // 淘汰与插入之间可能有并发插入，二次检查配额。
            require(sessionId !in entries) { "session already exists: $sessionId" }
            require(entries.size < maxSessions) {
                "profile $profileId reached the $maxSessions session limit; close an idle session first"
            }
            val components = factory.create(sessionId)
            val now = System.currentTimeMillis()
            Entry(components, now, now, isDefault = false).also { entries[sessionId] = it }
        }
        return entry.info(sessionId)
    }

    suspend fun list(): List<SessionInfo> {
        val ids = registryMutex.withLock { entries.keys.toList() }
        return buildList {
            for (id in ids) {
                val entry = registryMutex.withLock {
                    val found = entries[id] ?: return@withLock null
                    found.operationMutex.lock()
                    found
                } ?: continue
                try {
                    add(entry.info(id, entry.components.api.currentUrl()))
                } finally {
                    entry.operationMutex.unlock()
                }
            }
        }
    }

    suspend fun close(sessionId: String): Boolean {
        require(sessionId != DEFAULT_SESSION_ID) { "the default UI session cannot be closed" }
        val entry = registryMutex.withLock { entries.remove(sessionId) } ?: return false
        entry.operationMutex.withLock {
            entry.components.destroy()
        }
        return true
    }

    /**
     * 关闭空闲超过 [idleMs] 的非默认 session。按 lastAccess 判定，不会触碰正在执行操作的
     * session（操作期间 operationMutex 被持有，跳过而非等待，避免和活跃调用互相阻塞）。
     * 返回被关闭的 session id 列表。
     */
    suspend fun closeIdle(idleMs: Long): List<String> {
        require(idleMs >= 0) { "idleMs must be >= 0" }
        val cutoff = System.currentTimeMillis() - idleMs
        val victims = registryMutex.withLock {
            entries.filterValues { !it.isDefault && it.lastAccessEpochMs <= cutoff }
        }
        val closed = mutableListOf<String>()
        for ((id, entry) in victims) {
            // 尝试立即获取操作锁：拿不到说明正有操作在跑（刚活跃），放弃本次回收。
            if (!entry.operationMutex.tryLock()) continue
            val removed = registryMutex.withLock { if (entries[id] === entry) entries.remove(id) else null }
            try {
                if (removed != null) entry.components.destroy()
            } finally {
                entry.operationMutex.unlock()
            }
            if (removed != null) closed.add(id)
        }
        return closed
    }

    suspend fun closeAll() {
        val closing = registryMutex.withLock {
            entries.filterValues { !it.isDefault }.also { removable ->
                removable.keys.forEach(entries::remove)
            }
        }
        closing.values.forEach { entry ->
            entry.operationMutex.withLock {
                entry.components.destroy()
            }
        }
    }

    suspend fun <T> withApi(sessionId: String, block: suspend (DefaultPageKitApi) -> T): T {
        val entry = if (sessionId == DEFAULT_SESSION_ID) {
            val e = ensureDefault()
            e.operationMutex.lock()
            e
        } else {
            registryMutex.withLock {
                val found = entries[sessionId] ?: error("unknown session_id: $sessionId")
                // 在注册表锁内取得 operation lease，避免 close 在查找与执行之间销毁 WebView。
                found.operationMutex.lock()
                found
            }
        }
        return try {
            entry.lastAccessEpochMs = System.currentTimeMillis()
            publishActive(sessionId, entry)
            block(entry.components.api)
        } finally {
            entry.operationMutex.unlock()
        }
    }

    /** default 懒创建：仅当显式以 default 调用且尚无实例时，用注入的 default 组件建一个。 */
    private suspend fun ensureDefault(): Entry {
        registryMutex.withLock {
            entries[DEFAULT_SESSION_ID]?.let { return it }
            val components = requireNotNull(defaultFactory) {
                "this profile does not host the default UI session"
            }()
            val now = System.currentTimeMillis()
            return Entry(components, now, now, isDefault = true).also {
                entries[DEFAULT_SESSION_ID] = it
            }
        }
    }

    private fun publishActive(sessionId: String, entry: Entry) {
        _activeSessionId.value = sessionId
        _activeWebView.value = entry.components.webView
    }

    /**
     * 自动分配：优先复用空闲的非默认 session；全忙且未满则新建；满了排队最久未用的。
     * 返回 (session_id, 结果)，session_id 用于回显给调用方做后续固定路由。
     */
    suspend fun <T> withAnyApi(block: suspend (DefaultPageKitApi) -> T): Pair<String, T> {
        var existing = registryMutex.withLock {
            entries.values
                .filter { !it.isDefault }
                .sortedBy { it.lastAccessEpochMs }
                .firstOrNull { it.operationMutex.tryLock() }
                ?: entries.values
                    .filter { !it.isDefault }
                    .minByOrNull { it.lastAccessEpochMs }
                    ?.let { pick ->
                        registryMutex.unlock()
                        pick.operationMutex.lock()
                        registryMutex.lock()
                        pick
                    }
        }
        if (existing == null) {
            val sid = newSessionId(processSlot)
            existing = createInternal(sid)
            existing.operationMutex.lock()
        }
        val sid = registryMutex.withLock { sessionId(existing) }
        return try {
            existing.lastAccessEpochMs = System.currentTimeMillis()
            publishActive(sid, existing)
            sid to block(existing.components.api)
        } finally {
            existing.operationMutex.unlock()
        }
    }

    /** 创建非默认 session（不走公开 create 的两段式淘汰，专供内部池使用）。 */
    private suspend fun createInternal(sessionId: String): Entry {
        val components = factory.create(sessionId)
        val now = System.currentTimeMillis()
        val entry = Entry(components, now, now, isDefault = false)
        registryMutex.withLock {
            require(entries.size < maxSessions) {
                "profile $profileId reached the $maxSessions session limit"
            }
            entries[sessionId] = entry
        }
        return entry
    }

    /**
     * 调整容量后的收缩：销毁超配额的空闲 session（忙的等它下次回到池里自然淘汰）。
     * default 永不淘汰。
     */
    suspend fun shrinkToLimit() {
        val victims = registryMutex.withLock {
            val pool = entries.filterKeys { it != DEFAULT_SESSION_ID }
            val excess = (entries.size - maxSessions).coerceAtLeast(0)
            if (excess <= 0) return
            val candidates = pool.entries.sortedBy { it.value.lastAccessEpochMs }
            candidates.take(excess).mapNotNull { (id, entry) ->
                if (entry.operationMutex.tryLock()) {
                    entries.remove(id)
                    entry
                } else {
                    null
                }
            }
        }
        victims.forEach { entry ->
            try {
                entry.components.destroy()
            } finally {
                entry.operationMutex.unlock()
            }
        }
    }

    private fun sessionId(entry: Entry): String =
        entries.entries.firstOrNull { it.value === entry }?.key
            ?: error("session entry not registered")

    private fun Entry.info(sessionId: String, url: String = "") = SessionInfo(
        sessionId = sessionId,
        profileId = profileId,
        processSlot = processSlot,
        createdAtEpochMs = createdAtEpochMs,
        lastAccessEpochMs = lastAccessEpochMs,
        currentUrl = url,
        isDefault = isDefault,
    )

    companion object {
        val PROFILE_ID = Regex("[a-zA-Z0-9][a-zA-Z0-9._-]{0,31}")
        val SESSION_ID = Regex("(?:default|s[0-3]_[a-f0-9]{16})")
        private val random = java.security.SecureRandom()

        internal fun newSessionId(processSlot: Int): String {
            val bytes = ByteArray(8).also(random::nextBytes)
            return "s${processSlot}_" + bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }
    }
}
