package com.kenjc.pagekit.engine.adblock

import android.content.Context
import android.util.Log
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface HostsRuleRepository {
    fun snapshot(): HostsRuleSnapshot
}

/** 从 APK asset 异步装载固定规则；加载完成前采用空快照，不阻塞应用启动和 WebView IO 线程。 */
class AssetHostsRuleRepository(
    private val context: Context,
    private val assetPath: String = DEFAULT_ASSET_PATH,
    private val sourceVersion: String = SOURCE_VERSION,
    private val cacheFileName: String = CACHE_FILE_NAME,
) : HostsRuleRepository {

    private val current = AtomicReference(HostsRuleSnapshot.EMPTY)

    override fun snapshot(): HostsRuleSnapshot = current.get()

    fun start(scope: CoroutineScope) {
        scope.launch {
            runCatching { loadBestAvailable() }
                .onSuccess {
                    current.set(it)
                    Log.i(TAG, "hosts loaded: ${it.blockedDomains.size} domains, ${it.sourceVersion}")
                }
                .onFailure { Log.e(TAG, "hosts asset load failed; ad blocking remains disabled", it) }
        }
    }

    suspend fun reloadFromCache(): Boolean = withContext(Dispatchers.IO) {
        runCatching { loadCache() }
            .onSuccess { current.set(it) }
            .onFailure { Log.e(TAG, "hosts cache reload failed; keeping previous snapshot", it) }
            .isSuccess
    }

    private fun loadBestAvailable(): HostsRuleSnapshot = runCatching { loadCache() }
        .getOrElse { loadAsset() }

    private fun loadCache(): HostsRuleSnapshot {
        val file = context.filesDir.resolve("adblock/$cacheFileName")
        require(file.isFile) { "hosts cache missing" }
        val snapshot = HostsRuleParser.parse(file.bufferedReader().use { it.readText() }, "online-cache")
        require(snapshot.blockedDomains.size >= MIN_HOST_RULES) { "hosts cache has too few rules" }
        return snapshot
    }

    private fun loadAsset(): HostsRuleSnapshot {
        val text = context.assets.open(assetPath).use { raw ->
            GZIPInputStream(raw).bufferedReader().use { it.readText() }
        }
        return HostsRuleParser.parse(text, sourceVersion)
    }

    companion object {
        const val DEFAULT_ASSET_PATH = "adblock/stevenblack-hosts.dat"
        const val CACHE_FILE_NAME = "stevenblack-hosts.txt"
        const val MIN_HOST_RULES = 1_000
        const val SOURCE_VERSION = "StevenBlack/hosts@4731c9c341b13b9a4c8282a02eb551ab76090811"
        private const val TAG = "PageKit.AdBlock"
    }
}

class StaticHostsRuleRepository(
    private val rules: HostsRuleSnapshot,
) : HostsRuleRepository {
    override fun snapshot(): HostsRuleSnapshot = rules
}
