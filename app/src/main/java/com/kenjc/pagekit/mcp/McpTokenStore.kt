package com.kenjc.pagekit.mcp

import android.content.Context
import android.util.AtomicFile
import android.util.Base64
import java.security.SecureRandom

/**
 * 首次启动生成 256-bit token，持久化在应用数据里：重启、应用更新（install -r）均不变。
 * 仅在设置里手动「重置」或卸载/清除数据时才会更换（见 [reset]）。
 * 私有文件仅用于 adb run-as / 本地客户端读取。
 */
class McpTokenStore(
    private val context: Context,
) {
    @Volatile
    private var cached: String? = null

    val token: String
        get() = synchronized(this) {
            cached?.let { return it }
            val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            val value = preferences.getString(KEY_TOKEN, null)
                ?.takeIf { it.length >= 32 }
                ?: generateToken().also { preferences.edit().putString(KEY_TOKEN, it).commit() }
            writePrivateTokenFile(value)
            cached = value
            value
        }

    /** 手动重置：立即生效（运行中的 MCP 服务下一次请求即用新 token），并同步私有文件。 */
    fun reset(): String = synchronized(this) {
        val value = generateToken()
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit().putString(KEY_TOKEN, value).commit()
        writePrivateTokenFile(value)
        cached = value
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
        private const val PREFERENCES = "pagekit_mcp"
        private const val KEY_TOKEN = "bearer_token"
    }
}
