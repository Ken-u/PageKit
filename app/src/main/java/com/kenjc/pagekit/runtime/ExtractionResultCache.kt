package com.kenjc.pagekit.runtime

import com.kenjc.pagekit.api.dto.FetchRequest
import java.util.concurrent.ConcurrentHashMap

/**
 * 单 Session 的提取结果 LRU 缓存：
 * - 命中条件：url + mode + intent 完全一致，且期间无变更类操作（click/type/select/scroll/navigate/goBack/submit-hook）；
 * - 命中收益：跳过 WebView 加载 + Readability + html2md + 压缩全链路（秒级 → 微秒级）；
 * - 失效策略：任何互斥操作清空该 session 全部缓存（保守但正确：交互后页面状态不可预估）。
 */
class ExtractionResultCache(private val capacity: Int = 16) {

    private data class Key(val url: String, val mode: String, val intent: String?)

    private val store = object : LinkedHashMap<Key, RuntimePageResult>(capacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, RuntimePageResult>): Boolean =
            size > capacity
    }
    private val lock = Any()

    fun get(request: FetchRequest): RuntimePageResult? = synchronized(lock) {
        store[key(request)]?.let { cached ->
            cached.copy(durationMs = 0)
        }
    }

    fun put(request: FetchRequest, result: RuntimePageResult) {
        // 空结果不缓存：首次加载偶发失败（超时/空白页/提取为空）时，
        // 缓存空结果会让同 URL 一直秒回空，直到交互或重启才恢复。
        if (result.markdown.isBlank()) return
        synchronized(lock) {
            store[key(request)] = result
        }
    }

    /** 变更类操作后调用：页面状态已变，旧提取结果全部不可信。 */
    fun invalidateAll() {
        synchronized(lock) {
            store.clear()
        }
    }

    private fun key(request: FetchRequest): Key =
        Key(request.url, request.mode, request.intent?.takeIf { it.isNotBlank() })
}
