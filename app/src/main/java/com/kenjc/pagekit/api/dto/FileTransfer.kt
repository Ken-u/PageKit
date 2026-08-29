package com.kenjc.pagekit.api.dto

import kotlinx.serialization.Serializable

/** 下载请求命中的 WebView 侧回调（DownloadListener）暂存条目。 */
@Serializable
data class PendingDownload(
    val url: String,
    val suggestedFileName: String = "",
    val mimeType: String = "",
    val contentLength: Long = -1L,
    val atEpochMs: Long,
)

/** 一次文件下载的结果；path 相对于应用 filesDir，主进程与 worker 进程内含义一致。 */
@Serializable
data class FileDownloadResult(
    val fileName: String,
    val path: String,
    val sizeBytes: Long,
    val url: String,
    val contentType: String = "",
    val durationMs: Long,
    /** true = 由浏览器操作（click 等）触发的 pending 下载，而非显式 URL。 */
    val fromPending: Boolean = false,
)

/** 下载目录中的一个文件。 */
@Serializable
data class FileInfo(
    val fileName: String,
    val path: String,
    val sizeBytes: Long,
    val lastModifiedEpochMs: Long,
)
