package com.kenjc.pagekit.engine.adblock

import android.content.Context
import android.util.Log
import java.net.URI
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

interface CosmeticRuleRepository {
    fun snapshot(): CosmeticRuleSet
}

class AssetCosmeticRuleRepository(
    private val context: Context,
    private val assetPaths: List<String> = DEFAULT_ASSETS,
) : CosmeticRuleRepository {

    private val current = AtomicReference(CosmeticRuleSet.EMPTY)

    override fun snapshot(): CosmeticRuleSet = current.get()

    fun start(scope: CoroutineScope) {
        scope.launch {
            runCatching { load() }
                .onSuccess {
                    current.set(it)
                    Log.i(TAG, "cosmetic rules loaded: ${it.stats}")
                }
                .onFailure { Log.e(TAG, "cosmetic asset load failed", it) }
        }
    }

    private fun load(): CosmeticRuleSet = CosmeticRuleParser.parse(
        assetPaths.map { path ->
            context.assets.open(path).use { raw ->
                GZIPInputStream(raw).bufferedReader().use { it.readText() }
            }
        },
    )

    companion object {
        val DEFAULT_ASSETS = listOf("adblock/easylist.dat", "adblock/easylistchina.dat")
        private const val TAG = "PageKit.AdBlock"
    }
}

class StaticCosmeticRuleRepository(
    private val rules: CosmeticRuleSet,
) : CosmeticRuleRepository {
    override fun snapshot(): CosmeticRuleSet = rules
}

/** 将请求过滤与 DOM cosmetic 过滤组合成原有 AdBlocker SPI。 */
class CompositeAdBlocker(
    private val requestBlocker: AdBlocker,
    private val cosmeticRules: CosmeticRuleRepository,
) : AdBlocker {
    override fun shouldBlock(request: android.webkit.WebResourceRequest): Boolean = requestBlocker.shouldBlock(request)

    override fun elementHidingRules(pageUrl: String): List<String> {
        val host = runCatching { URI(pageUrl).host }.getOrNull() ?: return emptyList()
        return cosmeticRules.snapshot().selectorsFor(host)
    }
}
