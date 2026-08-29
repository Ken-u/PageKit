package com.kenjc.pagekit.download

import android.content.Context
import java.io.File

/**
 * 下载目录的只读解析入口，供 MCP server 的 `/files` 端点把落盘文件回传给调用方。
 *
 * 主进程与 profile worker 进程共享 filesDir，因此 worker 里的隔离 profile 下载的文件
 * 也能从这里取到。安全约束：只解析 [ROOT_DIR_NAME] 下的文件，namespace 与文件名都做
 * 白名单校验，canonical path 兜底防目录穿越。
 */
class DownloadFileStore(context: Context) {

    private val root: File = File(context.filesDir, ROOT_DIR_NAME).apply { mkdirs() }

    /** 解析下载目录中的文件；参数不合法或文件不存在返回 null。 */
    fun resolve(namespace: String, fileName: String): File? {
        if (!NAMESPACE.matches(namespace)) return null
        if (!FILE_NAME.matches(fileName)) return null
        val rootCanonical = root.canonicalPath
        val file = File(root, "$namespace/$fileName")
        return if (file.isFile && file.canonicalPath.startsWith(rootCanonical)) file else null
    }

    companion object {
        const val ROOT_DIR_NAME = "downloads"
        private const val NAMESPACE_PATTERN = "[a-zA-Z0-9._-]{1,80}"
        private const val FILE_NAME_PATTERN = "[a-zA-Z0-9._ ()-]{1,200}"

        /** session 下载目录名（如 p1_demo_s1_ab12...）→ /files 相对链接。 */
        val NAMESPACE = Regex(NAMESPACE_PATTERN)
        val FILE_NAME = Regex(FILE_NAME_PATTERN)

        /** FileDownloadResult/FileInfo.path（downloads/<ns>/<file>）→ /files/<ns>/<file>。 */
        fun downloadUrl(path: String): String? =
            path.takeIf { it.startsWith("$ROOT_DIR_NAME/") }
                ?.removePrefix("$ROOT_DIR_NAME/")
                ?.split('/')
                ?.takeIf { it.size == 2 && NAMESPACE.matches(it[0]) && FILE_NAME.matches(it[1]) }
                ?.joinToString(prefix = "/files/", separator = "/")
    }
}
