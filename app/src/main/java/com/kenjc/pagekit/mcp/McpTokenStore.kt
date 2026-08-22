package com.kenjc.pagekit.mcp

import android.content.Context
import android.util.AtomicFile
import android.util.Base64
import java.security.SecureRandom

/**
 * 首次启动生成 256-bit token，持久化在应用数据里：重启、应用更新（install -r）均不变。
 * 仅在设置里手动「重置」「设置为指定值」或卸载/清除数据时才会更换。
 * 私有文件仅用于 adb run-as / 本地客户端读取。
 *
 * 注意：不做实例级内存缓存——多个 store 实例（App/receiver/测试）必须看到同一个值，
 * SharedPreferences 自身已有进程内缓存，直读成本可忽略。
 */
class McpTokenStore(
    private val context: Context,
) {
    val token: String
        get() = synchronized(this) {
            val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            val value = preferences.getString(KEY_TOKEN, null)
                ?.takeIf { it.length >= 32 }
                ?: generateToken().also { preferences.edit().putString(KEY_TOKEN, it).commit() }
            writePrivateTokenFile(value)
            value
        }

    /** 手动重置：立即生效（运行中的 MCP 服务下一次请求即用新 token），并同步私有文件。 */
    fun reset(): String = synchronized(this) {
        val value = generateToken()
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit().putString(KEY_TOKEN, value).commit()
        writePrivateTokenFile(value)
        value
    }

    /**
     * 设置为调用方指定的 token（恢复/迁移用）：立即生效并持久化。
     * 值需满足 [TOKEN_PATTERN]（与自动生成的同格式，防止误存空白或含空格的串）。
     */
    fun set(value: String): String = synchronized(this) {
        require(TOKEN_PATTERN.matches(value)) { "invalid token: 32-128 位 A-Za-z0-9_-" }
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit().putString(KEY_TOKEN, value).commit()
        writePrivateTokenFile(value)
        value
    }

    private fun generateToken(): String {
        val bytes = ByteArray(32).also(SecureRandom()::nextBytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun writePrivateTokenFile(value: String) {
        val file = AtomicFile(context.filesDir.resolve(TOKEN_FILE))
        val output = file.startWrite()
        try {
            output.write(value.toByteArray(Charsets.UTF_8))
            output.write('\n'.code)
            file.finishWrite(output)
        } catch (error: Throwable) {
            file.failWrite(output)
            throw error
        }
    }

    companion object {
        const val TOKEN_FILE = "mcp_token.txt"
        val TOKEN_PATTERN = Regex("[A-Za-z0-9_-]{32,128}")
        private const val PREFERENCES = "pagekit_mcp"
        private const val KEY_TOKEN = "bearer_token"
    }
}
