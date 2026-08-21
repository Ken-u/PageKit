package com.kenjc.pagekit.engine.adblock

import android.content.Context
import android.util.Log
import java.net.URI
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface CosmeticRuleRepository {
    fun snapshot(): CosmeticRuleSet
}

class AssetCosmeticRuleRepository(
    private val context: Context,
    private val assetPaths: List<String> = DEFAULT_ASSETS,
    private val cacheFileNames: List<String> = CACHE_FILE_NAMES,
) : CosmeticRuleRepository {

    private val current = AtomicReference(CosmeticRuleSet.EMPTY)

    override fun snapshot(): CosmeticRuleSet = current.get()

    fun start(scope: CoroutineScope) {
        scope.launch {
            runCatching { loadBestAvailable() }
                .onSuccess {
                    current.set(it)
                    Log.i(TAG, "cosmetic rules loaded: ${it.stats}")
                }
                .onFailure { Log.e(TAG, "cosmetic asset load failed", it) }
        }
    }

    suspend fun reloadFromCache(): Boolean = withContext(Dispatchers.IO) {
        runCatching { loadBestAvailable(requireAtLeastOneCache = true) }
            .onSuccess { current.set(it) }
            .onFailure { Log.e(TAG, "cosmetic cache reload failed; keeping previous snapshot", it) }
            .isSuccess
    }

    private fun loadBestAvailable(requireAtLeastOneCache: Boolean = false): CosmeticRuleSet {
        if (requireAtLeastOneCache) return loadMixedSnapshot(requireAtLeastOneCache = true)
        return runCatching { loadMixedSnapshot(requireAtLeastOneCache = false) }
            .getOrElse {
                Log.w(TAG, "cosmetic cache invalid; falling back to bundled snapshot", it)
                parseAndValidate(assetPaths.map(::readAsset))
            }
    }

    private fun loadMixedSnapshot(requireAtLeastOneCache: Boolean): CosmeticRuleSet {
        require(assetPaths.size == cacheFileNames.size)
        var cached = 0
        val texts = assetPaths.zip(cacheFileNames).map { (assetPath, cacheName) ->
            val cache = context.filesDir.resolve("adblock/$cacheName")
            if (cache.isFile) {
                cached++
                cache.bufferedReader().use { it.readText() }
            } else {
                readAsset(assetPath)
            }
        }
        if (requireAtLeastOneCache) require(cached > 0) { "cosmetic cache missing" }
        return parseAndValidate(texts)
    }

    private fun parseAndValidate(texts: List<String>): CosmeticRuleSet {
        val rules = CosmeticRuleParser.parse(texts)
        require(rules.stats.accepted >= MIN_COSMETIC_RULES) { "cosmetic cache has too few supported rules" }
        return rules
    }

    private fun readAsset(assetPath: String): String = context.assets.open(assetPath).use { raw ->
        GZIPInputStream(raw).bufferedReader().use { it.readText() }
    }

    companion object {
        val DEFAULT_ASSETS = listOf("adblock/easylist.dat", "adblock/easylistchina.dat")
        val CACHE_FILE_NAMES = listOf("easylist.txt", "easylistchina.txt")
        const val MIN_COSMETIC_RULES = 1_000
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
