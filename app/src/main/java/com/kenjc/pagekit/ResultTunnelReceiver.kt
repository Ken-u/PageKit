package com.kenjc.pagekit

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.io.File

/**
 * adb 验证通道（PLAN.md：实机验证）：
 *
 *   adb shell am broadcast -a com.kenjc.pagekit.FETCH_RESULT --es format json \
 *        -n com.kenjc.pagekit/.ResultTunnelReceiver
 *
 * 读取最近一次提取结果（filesDir/last_result.txt：markdown + ===JSON=== 分隔 + JSON）。
 * 注：部分 ROM 将 exported receiver 派发到独立进程，故不依赖进程内单例，走磁盘。
 */
class ResultTunnelReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val file = File(context.filesDir, "last_result.txt")
        if (!file.exists()) {
            resultCode = -1
            resultData = "no result file (load page with --ez extract true first)"
            return
        }
        val content = file.readText()
        val wantJson = intent.getStringExtra("format") == "json"
        val payload = if (wantJson) {
            content.substringAfter("\n===JSON===\n", "").ifBlank { content }
        } else {
            content.substringBefore("\n===JSON===")
        }.trim()
        resultCode = payload.length
        resultData = payload
    }
}
