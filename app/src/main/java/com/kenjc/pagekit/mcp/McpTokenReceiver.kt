package com.kenjc.pagekit.mcp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.kenjc.pagekit.net.ProxyConfig
import com.kenjc.pagekit.net.SharedPreferencesProxySettings
import com.kenjc.pagekit.net.WebViewProxyApplier
import com.kenjc.pagekit.ui.screensaver.ScreensaverSettings

/**
 * 开发者配置通道的 adb 入口（release 包无法 run-as）：
 *
 * 读 token： am broadcast -a com.kenjc.pagekit.MCP_TOKEN -n .../.mcp.McpTokenReceiver
 *            → logcat（tag=PageKit.McpToken）输出 pin=<PIN> token=<TOKEN>
 *
 * 写配置（PIN 校验，可组合）：
 *   --es pin <PIN>
 *   --es set <TOKEN>                        设置 token（恢复客户端配置）
 *   --es proxy_host <h> --ei proxy_port <p> [--ez proxy_enabled true] [--es proxy_bypass <b>]
 *   --el screensaver_timeout_ms <ms>        屏保闲置时长（0=关闭）
 *   --ei max_sessions <n>                   每 Profile 最大并发 session
 *
 * 写操作原子提交各配置项后统一应用：代理立即 setProxyOverride，并发数立即收缩。
 * PIN 错误连续 5 次锁定 10 分钟。
 */
class McpTokenReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val pin = PinStore(context).get()
        val writeRequested = listOf(EXTRA_SET, EXTRA_PROXY_HOST, EXTRA_SCREENSAVER_MS, EXTRA_MAX_SESSIONS)
            .any { intent.hasExtra(it) }

        if (writeRequested) {
            if (isLockedOut()) {
                Log.w(TAG, "config write rejected: locked until ${lockoutUntilEpochMs.get()}")
                return
            }
            if (intent.getStringExtra(EXTRA_PIN)?.trim() != pin) {
                registerFailure()
                Log.w(TAG, "config write rejected: invalid pin")
                return
            }
            resetFailures()
            applyConfig(context, intent)
            return
        }

        // 读路径：PIN+token 只进 logcat
        Log.i(TAG, "pin=$pin token=${McpTokenStore(context).token}")
    }

    private fun applyConfig(context: Context, intent: Intent) {
        val applied = mutableListOf<String>()

        intent.getStringExtra(EXTRA_SET)?.trim()?.takeIf { it.isNotEmpty() }?.let { token ->
            runCatching { McpTokenStore(context).set(token) }
                .onSuccess { applied += "token" }
                .onFailure { Log.w(TAG, "set token failed: ${it.message}") }
        }

        if (intent.hasExtra(EXTRA_PROXY_HOST) || intent.hasExtra(EXTRA_PROXY_PORT)) {
            val current = SharedPreferencesProxySettings(context).load()
            val config = ProxyConfig(
                enabled = intent.getBooleanExtra(EXTRA_PROXY_ENABLED, current.enabled),
                host = intent.getStringExtra(EXTRA_PROXY_HOST)?.trim().orEmpty()
                    .ifEmpty { current.host },
                port = intent.getIntExtra(EXTRA_PROXY_PORT, current.port),
                bypass = intent.getStringExtra(EXTRA_PROXY_BYPASS)?.trim() ?: current.bypass,
            )
            if (config.enabled && (config.host.isBlank() || config.port !in 1..65535)) {
                Log.w(TAG, "proxy config invalid: host=${config.host} port=${config.port}")
            } else {
                SharedPreferencesProxySettings(context).save(config)
                WebViewProxyApplier.apply(config)
                applied += "proxy(${config.host}:${config.port}${if (config.enabled) "" else ",disabled"})"
            }
        }

        if (intent.hasExtra(EXTRA_SCREENSAVER_MS)) {
            val value = intent.getLongExtra(EXTRA_SCREENSAVER_MS, -1)
            if (value >= 0) {
                ScreensaverSettings(context).save(value)
                applied += "screensaver=${value}ms"
            }
        }

        if (intent.hasExtra(EXTRA_MAX_SESSIONS)) {
            val value = intent.getIntExtra(EXTRA_MAX_SESSIONS, -1)
            if (value in 1..8) {
                ScreensaverSettings(context).saveMaxSessions(value)
                applied += "max_sessions=$value"
            }
        }

        Log.i(TAG, "config applied: ${applied.joinToString(", ").ifEmpty { "nothing" } }")
    }

    companion object {
        const val ACTION = "com.kenjc.pagekit.MCP_TOKEN"
        const val EXTRA_PIN = "pin"
        const val EXTRA_SET = "set"
        const val EXTRA_PROXY_HOST = "proxy_host"
        const val EXTRA_PROXY_PORT = "proxy_port"
        const val EXTRA_PROXY_ENABLED = "proxy_enabled"
        const val EXTRA_PROXY_BYPASS = "proxy_bypass"
        const val EXTRA_SCREENSAVER_MS = "screensaver_timeout_ms"
        const val EXTRA_MAX_SESSIONS = "max_sessions"
        private const val TAG = "PageKit.McpToken"

        private const val MAX_FAILURES = 5
        private const val LOCKOUT_MS = 10 * 60_000L

        private val failureCount = java.util.concurrent.atomic.AtomicInteger(0)
        private val lockoutUntilEpochMs = java.util.concurrent.atomic.AtomicLong(0)

        private fun isLockedOut(): Boolean =
            System.currentTimeMillis() < lockoutUntilEpochMs.get()

        private fun registerFailure() {
            if (failureCount.incrementAndGet() >= MAX_FAILURES) {
                lockoutUntilEpochMs.set(System.currentTimeMillis() + LOCKOUT_MS)
                failureCount.set(0)
            }
        }

        private fun resetFailures() {
            failureCount.set(0)
            lockoutUntilEpochMs.set(0)
        }
    }
}

/** PIN 生成与持久化：与 token 同目录同权限，六位数字。 */
private class PinStore(private val context: Context) {
    fun get(): String {
        val file = context.filesDir.resolve("mcp_pin.txt")
        file.takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.length == 6 }?.let { return it }
        val pin = (100000..999999).random().toString()
        file.writeText(pin)
        return pin
    }
}
