package com.kenjc.pagekit.engine.adblock

import android.content.Context
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest

/**
 * 规则解析结果的二进制快照缓存：把"读文本+逐行解析"（秒级）变成"顺序读二进制"（毫秒级）。
 * 快照头带格式版本与源文本指纹，源变化（在线更新写入新文本）时自动失效重建。
 */
internal object RuleSnapshotCache {

    private const val FORMAT_VERSION = 1

    fun file(context: Context, name: String): File =
        context.filesDir.resolve("adblock/snapshots/$name.snap")

    /** 源文件（文本缓存或 asset 解压后的等价物）的指纹：大小 + SHA-256 前 8 字节。 */
    fun fingerprint(texts: List<String>): Long {
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        for (text in texts) {
            val bytes = text.toByteArray(Charsets.UTF_8)
            size += bytes.size
            digest.update(bytes)
        }
        val hash = digest.digest()
        var fp = size
        for (i in 0 until 8) fp = (fp shl 8) or (hash[i].toLong() and 0xff)
        return fp
    }

    fun write(context: Context, name: String, fingerprint: Long, body: (java.io.DataOutputStream) -> Unit) {
        val target = file(context, name)
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, "${target.name}.tmp")
        java.io.DataOutputStream(tmp.outputStream().buffered()).use { out ->
            out.writeInt(FORMAT_VERSION)
            out.writeLong(fingerprint)
            body(out)
        }
        if (target.isFile) target.delete()
        if (!tmp.renameTo(target)) {
            tmp.delete()
            error("snapshot rename failed: $target")
        }
    }

    /** 快照存在且指纹匹配才执行 [body]；任何不一致都返回 false（调用方回退文本解析）。 */
    fun read(context: Context, name: String, fingerprint: Long, body: (java.io.DataInputStream) -> Unit): Boolean {
        val target = file(context, name)
        if (!target.isFile) return false
        return runCatching {
            java.io.DataInputStream(target.inputStream().buffered()).use { input ->
                val version = input.readInt()
                val fp = input.readLong()
                require(version == FORMAT_VERSION && fp == fingerprint)
                body(input)
            }
            true
        }.getOrElse {
            target.delete()
            false
        }
    }
}
