package com.kenjc.pagekit.mcp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 开发者 token 通道的 adb 入口（release 包无法 run-as）：
 *
 * 读： adb shell am broadcast -a com.kenjc.pagekit.MCP_TOKEN \
 *          -n com.kenjc.pagekit/.mcp.McpTokenReceiver
 *      → PIN+token 打到 logcat（tag=PageKit.McpToken）
 *
 * 写： adb shell am broadcast -a com.kenjc.pagekit.MCP_TOKEN \
 *          -n com.kenjc.pagekit/.mcp.McpTokenReceiver \
 *          --es pin <PIN> --es set <TOKEN>
 *      → 校验 PIN 后把 token 设为指定值（换机/重装后恢复原配置）。
 *
 * PIN 六位、首次生成后固定（应用私有文件）；logcat 普通应用不可读。
 * exported receiver 同机任意应用可触发，写操作需 PIN 且带失败锁定
 * （连续 5 次错误锁定 10 分钟），读取仅输出到 logcat 不返回给广播方。
 */
class McpTokenReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val pin = PinStore(context).get()
        val requested = intent.getStringExtra(EXTRA_PIN)?.trim()
        val newToken = intent.getStringExtra(EXTRA_SET)?.trim()

        if (newToken != null) {
            if (isLockedOut()) {
                Log.w(TAG, "set rejected: too many invalid pins, locked until ${lockoutUntilEpochMs.get()}")
                return
            }
            if (requested != pin) {
                registerFailure()
                Log.w(TAG, "set rejected: invalid pin")
                return
            }
            resetFailures()
            runCatching { McpTokenStore(context).set(newToken) }
                .onSuccess { Log.i(TAG, "token updated via adb set") }
                .onFailure { Log.w(TAG, "set failed: ${it.message}") }
            return
        }

        // 读路径：PIN+token 只进 logcat（广播结果在部分 ROM 上不回显，且对任意触发方可读不安全）
        Log.i(TAG, "pin=$pin token=${McpTokenStore(context).token}")
    }

    companion object {
        const val ACTION = "com.kenjc.pagekit.MCP_TOKEN"
        const val EXTRA_PIN = "pin"
        const val EXTRA_SET = "set"
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
