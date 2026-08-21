package com.kenjc.pagekit.engine.adblock

import android.content.Context
import android.util.Log
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

interface HostsRuleRepository {
    fun snapshot(): HostsRuleSnapshot
}

/** 从 APK asset 异步装载固定规则；加载完成前采用空快照，不阻塞应用启动和 WebView IO 线程。 */
class AssetHostsRuleRepository(
    private val context: Context,
    private val assetPath: String = DEFAULT_ASSET_PATH,
    private val sourceVersion: String = SOURCE_VERSION,
) : HostsRuleRepository {

    private val current = AtomicReference(HostsRuleSnapshot.EMPTY)

    override fun snapshot(): HostsRuleSnapshot = current.get()

    fun start(scope: CoroutineScope) {
        scope.launch {
            runCatching { load() }
                .onSuccess {
                    current.set(it)
                    Log.i(TAG, "hosts loaded: ${it.blockedDomains.size} domains, ${it.sourceVersion}")
                }
                .onFailure { Log.e(TAG, "hosts asset load failed; ad blocking remains disabled", it) }
        }
    }

    private fun load(): HostsRuleSnapshot {
        val text = context.assets.open(assetPath).use { raw ->
            GZIPInputStream(raw).bufferedReader().use { it.readText() }
        }
        return HostsRuleParser.parse(text, sourceVersion)
    }

    companion object {
        const val DEFAULT_ASSET_PATH = "adblock/stevenblack-hosts.dat"
        const val SOURCE_VERSION = "StevenBlack/hosts@4731c9c341b13b9a4c8282a02eb551ab76090811"
        private const val TAG = "PageKit.AdBlock"
    }
}

class StaticHostsRuleRepository(
    private val rules: HostsRuleSnapshot,
) : HostsRuleRepository {
    override fun snapshot(): HostsRuleSnapshot = rules
}
