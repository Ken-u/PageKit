package com.kenjc.pagekit.download

import android.content.Context
import android.webkit.CookieManager
import com.kenjc.pagekit.api.dto.FileDownloadResult
import com.kenjc.pagekit.api.dto.PendingDownload
import com.kenjc.pagekit.net.SharedPreferencesProxySettings
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 单文件下载上限；旧设备不给打爆存储的机会。 */
const val MAX_DOWNLOAD_BYTES = 100L * 1024 * 1024

/**
 * 原生 HTTP 文件下载内核：不经过 WebView 渲染链路，直连拉取字节。
 *
 * - 复用 WebView 的 Cookie（登录态下载的核心：CookieManager 与 WebView 同进程共享）
 * - 复用代理配置（与 AndroidPageKitRuntime.fetchRawContent 同款）
 * - 临时文件写入 + 原子重命名，失败不留半截文件
 */
class FileDownloader(private val appContext: Context) {

    /**
     * 下载 [url] 到 [destDir]。[suggestedFileName] 来自 DownloadListener 回调或 pending 队列。
     * 返回落盘结果；磁盘/网络错误抛 IOException，超限抛 IllegalStateException。
     */
    suspend fun download(
        url: String,
        destDir: File,
        suggestedFileName: String = "",
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): FileDownloadResult = withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()
        destDir.mkdirs()
        val tempFile = File.createTempFile("download-", ".part", destDir)
        try {
            val (fileName, contentType, size) = transfer(url, tempFile, suggestedFileName, timeoutMs)
            val finalFile = resolveTarget(destDir, fileName)
            check(tempFile.renameTo(finalFile)) { "failed to move downloaded file into place" }
            FileDownloadResult(
                fileName = finalFile.name,
                path = finalFile.relativeTo(appContext.filesDir).path,
                sizeBytes = finalFile.length(),
                url = url,
                contentType = contentType,
                durationMs = System.currentTimeMillis() - startedAt,
            )
        } finally {
            if (tempFile.exists()) tempFile.delete()
        }
    }

    /** 返回 (最终文件名, Content-Type, 字节数)。 */
    private fun transfer(
        url: String,
        tempFile: File,
        suggestedFileName: String,
        timeoutMs: Long,
    ): Triple<String, String, Long> {
        val connection = open(url) ?: throw IOException("unsupported URL: $url")
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", USER_AGENT)
            CookieManager.getInstance().getCookie(url)?.let { connection.setRequestProperty("Cookie", it) }
            connection.connect()

            val status = connection.responseCode
            check(status in 200..299) { "download failed: HTTP $status for $url" }

            val contentType = connection.contentType.orEmpty()
            val fileName = resolveFileName(
                header = connection.getHeaderField("Content-Disposition").orEmpty(),
                url = connection.url?.toString().orEmpty(),
                suggested = suggestedFileName,
                contentType = contentType,
            )

            var total = 0L
            connection.inputStream.use { input ->
                tempFile.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_DOWNLOAD_BYTES) {
                            throw IllegalStateException(
                                "download exceeds the ${MAX_DOWNLOAD_BYTES / (1024 * 1024)} MiB limit",
                            )
                        }
                        output.write(buffer, 0, read)
                    }
                }
            }
            check(total > 0) { "downloaded file is empty" }
            return Triple(fileName, contentType, total)
        } finally {
            connection.disconnect()
        }
    }

    private fun open(rawUrl: String): HttpURLConnection? = runCatching {
        val url = URL(rawUrl)
        if (url.protocol != "http" && url.protocol != "https") return null
        val config = SharedPreferencesProxySettings(appContext).load()
        val connection = if (config.enabled) {
            val proxy = java.net.Proxy(
                java.net.Proxy.Type.HTTP,
                java.net.InetSocketAddress(config.host, config.port),
            )
            url.openConnection(proxy) as HttpURLConnection
        } else {
            url.openConnection() as HttpURLConnection
        }
        connection
    }.getOrNull()

    /** 文件名优先级：suggested（DownloadListener）> Content-Disposition > URL 末段 > 下载时间戳。 */
    private fun resolveFileName(header: String, url: String, suggested: String, contentType: String): String {
        val fromHeader = Regex("filename\\*?=(?:UTF-8''|\")?([^\";]+)", RegexOption.IGNORE_CASE)
            .find(header)?.groupValues?.get(1)
            ?.let { runCatching { URLDecoder.decode(it.trim(), "UTF-8") }.getOrNull() }
        val fromUrl = runCatching { URL(url).path.substringAfterLast('/') }.getOrNull().orEmpty()
        val candidate = sanitizeFileName(
            listOf(suggested, fromHeader, fromUrl).firstOrNull { !it.isNullOrBlank() }.orEmpty(),
        )
        return when {
            candidate.isNotBlank() && candidate.contains('.') -> candidate
            candidate.isNotBlank() -> "$candidate.${extensionFor(contentType)}"
            else -> "download-${System.currentTimeMillis()}.${extensionFor(contentType)}"
        }
    }

    private fun resolveTarget(destDir: File, fileName: String): File {
        // 同名不覆盖：追加序号，避免并发下载互相踩踏。
        val (base, ext) = fileName.substringBeforeLast('.') to ".${fileName.substringAfterLast('.')}"
        var target = File(destDir, fileName)
        var index = 1
        while (target.exists()) {
            target = File(destDir, "$base-$index$ext")
            index++
        }
        return target
    }

    companion object {
        private const val DEFAULT_TIMEOUT_MS = 5L * 60 * 1000
        private const val READ_TIMEOUT_MS = 30_000
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android) PageKit/1.0"
        private val FILE_NAME_FORBIDDEN = Regex("[/\\\\]|\u0000")

        fun sanitizeFileName(name: String): String =
            name.trim().replace(FILE_NAME_FORBIDDEN, "_").take(200)

        private fun extensionFor(contentType: String): String = when {
            contentType.contains("pdf", true) -> "pdf"
            contentType.contains("zip", true) -> "zip"
            contentType.contains("gzip", true) -> "gz"
            contentType.contains("csv", true) -> "csv"
            contentType.contains("json", true) -> "json"
            contentType.contains("html", true) -> "html"
            contentType.contains("png", true) -> "png"
            contentType.contains("jpeg", true) || contentType.contains("jpg", true) -> "jpg"
            contentType.contains("plain", true) -> "txt"
            else -> "bin"
        }
    }
}

/** 从 DownloadListener 回调参数组装 pending 条目。 */
fun pendingDownloadOf(url: String, contentDisposition: String?, mimeType: String?, contentLength: Long): PendingDownload {
    val suggested = Regex("filename\\*?=(?:UTF-8''|\")?([^\";]+)", RegexOption.IGNORE_CASE)
        .find(contentDisposition.orEmpty())?.groupValues?.get(1)
        ?.let { runCatching { URLDecoder.decode(it.trim(), "UTF-8") }.getOrNull() }
        ?.let(FileDownloader::sanitizeFileName)
        ?: runCatching { URL(url).path.substringAfterLast('/') }.getOrNull().orEmpty()
    return PendingDownload(
        url = url,
        suggestedFileName = suggested,
        mimeType = mimeType.orEmpty(),
        contentLength = contentLength,
        atEpochMs = System.currentTimeMillis(),
    )
}
