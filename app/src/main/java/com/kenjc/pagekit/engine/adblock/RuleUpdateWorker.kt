package com.kenjc.pagekit.engine.adblock

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.kenjc.pagekit.PageKitApp
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

data class RuleSource(
    val id: String,
    val url: String,
    val cacheFileName: String,
    val maxBytes: Int,
)

sealed interface RuleFetchResult {
    data class Downloaded(
        val bytes: ByteArray,
        val etag: String?,
        val lastModified: String?,
    ) : RuleFetchResult

    data object NotModified : RuleFetchResult
}

fun interface RuleHttpConnectionFactory {
    fun open(url: URL): HttpURLConnection
}

/** HTTPS-only、带条件请求和体积上限的规则下载器。 */
class RuleHttpDownloader(
    private val connectionFactory: RuleHttpConnectionFactory = RuleHttpConnectionFactory {
        it.openConnection() as HttpURLConnection
    },
) {
    fun fetch(source: RuleSource, etag: String?, lastModified: String?): RuleFetchResult {
        var current = URL(source.url)
        require(current.protocol == "https") { "rule source must use HTTPS" }
        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            val connection = connectionFactory.open(current)
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = CONNECT_TIMEOUT_MS
                connection.readTimeout = READ_TIMEOUT_MS
                connection.setRequestProperty("User-Agent", "PageKit/${BuildConfigVersion.NAME}")
                connection.setRequestProperty("Accept", "text/plain")
                etag?.let { connection.setRequestProperty("If-None-Match", it) }
                lastModified?.let { connection.setRequestProperty("If-Modified-Since", it) }
                when (val code = connection.responseCode) {
                    HttpURLConnection.HTTP_NOT_MODIFIED -> return RuleFetchResult.NotModified
                    HttpURLConnection.HTTP_OK -> {
                        val declaredLength = connection.contentLengthLong
                        if (declaredLength > source.maxBytes) throw IOException("rule response exceeds size limit")
                        val bytes = connection.inputStream.use { input ->
                            val output = ByteArrayOutputStream(minOf(source.maxBytes, 64 * 1024))
                            val buffer = ByteArray(16 * 1024)
                            var total = 0
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                total += count
                                if (total > source.maxBytes) throw IOException("rule response exceeds size limit")
                                output.write(buffer, 0, count)
                            }
                            output.toByteArray()
                        }
                        if (bytes.isEmpty()) throw IOException("empty rule response")
                        return RuleFetchResult.Downloaded(
                            bytes = bytes,
                            etag = connection.getHeaderField("ETag"),
                            lastModified = connection.getHeaderField("Last-Modified"),
                        )
                    }
                    in 300..399 -> {
                        if (redirectCount >= MAX_REDIRECTS) throw IOException("too many redirects")
                        val location = connection.getHeaderField("Location")
                            ?: throw IOException("redirect without Location")
                        current = URL(current, location)
                        if (current.protocol != "https") throw IOException("insecure rule redirect")
                    }
                    else -> throw IOException("rule source returned HTTP $code")
                }
            } finally {
                connection.disconnect()
            }
        }
        throw IOException("too many redirects")
    }

    private companion object {
        const val MAX_REDIRECTS = 4
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 30_000
    }
}

/** 下载、解析验证、原子落盘，再热切换内存快照。 */
class RuleUpdateWorker(
    appContext: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(appContext, parameters) {

    override suspend fun doWork(): Result {
        val app = applicationContext as PageKitApp
        val directory = applicationContext.filesDir.resolve(CACHE_DIRECTORY).apply { mkdirs() }
        val metadata = applicationContext.getSharedPreferences(METADATA_PREFERENCES, Context.MODE_PRIVATE)
        return try {
            val results = SOURCES.associateWith { source ->
                val cacheExists = directory.resolve(source.cacheFileName).isFile
                downloader.fetch(
                    source = source,
                    etag = metadata.getString("${source.id}.etag", null).takeIf { cacheExists },
                    lastModified = metadata.getString("${source.id}.last_modified", null).takeIf { cacheExists },
                )
            }
            validateCandidates(directory, results)
            val editor = metadata.edit()
            results.forEach { (source, result) ->
                if (result !is RuleFetchResult.Downloaded) return@forEach
                AtomicFile(directory.resolve(source.cacheFileName)).writeFully(result.bytes)
                editor.putString("${source.id}.etag", result.etag)
                editor.putString("${source.id}.last_modified", result.lastModified)
            }
            check(editor.commit()) { "failed to persist rule metadata" }
            check(app.hostsRuleRepository.reloadFromCache()) { "failed to activate hosts cache" }
            check(app.cosmeticRuleRepository.reloadFromCache()) { "failed to activate cosmetic cache" }
            Log.i(TAG, "online adblock rules updated")
            Result.success()
        } catch (error: IOException) {
            Log.w(TAG, "rule update network failure; bundled/current snapshot remains active", error)
            Result.retry()
        } catch (error: Throwable) {
            Log.e(TAG, "rule update rejected; bundled/current snapshot remains active", error)
            Result.failure()
        }
    }

    private fun validateCandidates(
        directory: java.io.File,
        results: Map<RuleSource, RuleFetchResult>,
    ) {
        val hostsText = candidateText(HOSTS, directory, results, "adblock/stevenblack-hosts.dat")
        val hosts = HostsRuleParser.parse(hostsText, "online-candidate")
        require(hosts.blockedDomains.size >= AssetHostsRuleRepository.MIN_HOST_RULES) {
            "online hosts snapshot has too few rules"
        }
        val cosmetic = CosmeticRuleParser.parse(
            listOf(
                candidateText(EASYLIST, directory, results, "adblock/easylist.dat"),
                candidateText(EASYLIST_CHINA, directory, results, "adblock/easylistchina.dat"),
            ),
        )
        require(cosmetic.stats.accepted >= AssetCosmeticRuleRepository.MIN_COSMETIC_RULES) {
            "online cosmetic snapshot has too few supported rules"
        }
    }

    private fun candidateText(
        source: RuleSource,
        directory: java.io.File,
        results: Map<RuleSource, RuleFetchResult>,
        assetPath: String,
    ): String {
        val downloaded = results[source] as? RuleFetchResult.Downloaded
        if (downloaded != null) return downloaded.bytes.toString(Charsets.UTF_8)
        val cache = directory.resolve(source.cacheFileName)
        if (cache.isFile) return cache.bufferedReader().use { it.readText() }
        return applicationContext.assets.open(assetPath).use { raw ->
            GZIPInputStream(raw).bufferedReader().use { it.readText() }
        }
    }

    private fun AtomicFile.writeFully(bytes: ByteArray) {
        val output = startWrite()
        try {
            output.write(bytes)
            finishWrite(output)
        } catch (error: Throwable) {
            failWrite(output)
            throw error
        }
    }

    companion object {
        const val WORK_NAME = "pagekit-adblock-rule-update"
        const val CACHE_DIRECTORY = "adblock"
        const val METADATA_PREFERENCES = "pagekit_adblock_updates"
        private const val TAG = "PageKit.AdBlock"
        private const val MAX_RULE_BYTES = 12 * 1024 * 1024

        val HOSTS = RuleSource(
            id = "stevenblack-hosts",
            url = "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts",
            cacheFileName = AssetHostsRuleRepository.CACHE_FILE_NAME,
            maxBytes = MAX_RULE_BYTES,
        )
        val EASYLIST = RuleSource(
            id = "easylist",
            url = "https://easylist.to/easylist/easylist.txt",
            cacheFileName = "easylist.txt",
            maxBytes = MAX_RULE_BYTES,
        )
        val EASYLIST_CHINA = RuleSource(
            id = "easylist-china",
            url = "https://easylist-downloads.adblockplus.org/easylistchina.txt",
            cacheFileName = "easylistchina.txt",
            maxBytes = MAX_RULE_BYTES,
        )
        val SOURCES = listOf(HOSTS, EASYLIST, EASYLIST_CHINA)

        private val downloader = RuleHttpDownloader()
    }
}

object RuleUpdateScheduler {
    fun schedule(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = PeriodicWorkRequestBuilder<RuleUpdateWorker>(7, TimeUnit.DAYS)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            RuleUpdateWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }
}

/** 避免规则更新协议把应用 BuildConfig 引入纯 JVM 测试。 */
private object BuildConfigVersion {
    const val NAME = "0.2"
}
