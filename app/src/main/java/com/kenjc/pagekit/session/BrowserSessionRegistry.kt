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

    suspend fun create(sessionId: String): SessionInfo {
        require(SESSION_ID.matches(sessionId)) { "invalid session_id" }
        val entry = registryMutex.withLock {
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
