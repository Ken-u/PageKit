package com.kenjc.pagekit.session

import com.kenjc.pagekit.api.DefaultPageKitApi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class BrowserSessionComponents(
    val api: DefaultPageKitApi,
    val destroy: suspend () -> Unit,
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
    private val maxSessions: Int = 4,
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

    init {
        require(PROFILE_ID.matches(profileId)) { "invalid profile_id" }
        require(processSlot in 0..3) { "invalid process slot" }
        require(maxSessions in 1..8) { "maxSessions must be between 1 and 8" }
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
        val entry = registryMutex.withLock {
            val found = entries[sessionId] ?: error("unknown session_id: $sessionId")
            // 在注册表锁内取得 operation lease，避免 close 在查找与执行之间销毁 WebView。
            found.operationMutex.lock()
            found
        }
        return try {
            entry.lastAccessEpochMs = System.currentTimeMillis()
            block(entry.components.api)
        } finally {
            entry.operationMutex.unlock()
        }
    }

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
    }
}
