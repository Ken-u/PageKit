package com.kenjc.pagekit.mcp

import android.content.Context
import android.util.AtomicFile
import android.util.Base64
import java.security.SecureRandom

/** 首次启动生成 256-bit token；私有文件仅用于 adb run-as / 本地客户端读取。 */
class McpTokenStore(
    private val context: Context,
) {
    val token: String by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val value = preferences.getString(KEY_TOKEN, null)
            ?.takeIf { it.length >= 32 }
            ?: generateToken().also { preferences.edit().putString(KEY_TOKEN, it).commit() }
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
        private const val PREFERENCES = "pagekit_mcp"
        private const val KEY_TOKEN = "bearer_token"
    }
}
