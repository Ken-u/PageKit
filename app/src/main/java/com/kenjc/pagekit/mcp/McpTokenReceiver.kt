package com.kenjc.pagekit.mcp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 开发者取 token 的 adb 通道（release 包无法 run-as）：
 *
 *   adb shell am broadcast -a com.kenjc.pagekit.MCP_TOKEN
 *   adb shell "logcat -d -s PageKit.McpToken | tail -1"     # PIN+token 一起打出来
 *
 * 无需参数：PIN 首次自动生成（六位数字，固定持久化），每次调用都把
 * "pin=<PIN> token=<TOKEN>" 打到 logcat（tag=PageKit.McpToken）。
 * adb 本身已是信任边界（需开发者选项+物理接触）；logcat 读取同样需要
 * adb 权限，不扩大暴露面。exported receiver 同机任意应用可触发，但只能
 * 触发打印（读不到结果），PIN 在 logcat 里而 logcat 普通应用不可读。
 *
 * 也可带 --es pin <PIN> 静默校验：PIN 正确时把 token 写入应用缓存目录
 * filesDir/mcp_token_export.txt 供 adb pull 之外的脚本消费（可选流程）。
 */
class McpTokenReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val pin = PinStore(context).get()
        val token = McpTokenStore(context).token
        Log.i(TAG, "pin=$pin token=$token")
        if (intent.getStringExtra(EXTRA_PIN)?.trim() == pin) {
            context.filesDir.resolve(EXPORT_FILE).writeText(token)
        }
    }

    companion object {
        const val ACTION = "com.kenjc.pagekit.MCP_TOKEN"
        const val EXTRA_PIN = "pin"
        const val EXPORT_FILE = "mcp_token_export.txt"
        private const val TAG = "PageKit.McpToken"
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
