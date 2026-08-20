package com.kenjc.pagekit

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.kenjc.pagekit.ui.home.HomeViewModel

/**
 * adb 验证通道（PLAN.md：实机验证）：
 *
 *   adb shell am broadcast -a com.kenjc.pagekit.FETCH_RESULT \
 *        -n com.kenjc.pagekit/.ResultTunnelReceiver
 *
 * 最近一次提取结果以 ordered broadcast 返回（resultCode = dataLength，data 为 Markdown）。
 * 亦为 V2 导出/MCP fetch 提供无 UI 读取路径。
 */
class ResultTunnelReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? PageKitApp
        val state = app?.homeViewModel?.extractState?.value
        val load = app?.homeViewModel?.loadState?.value
        if (state == null || !state.ok) {
            resultCode = -1
            resultData = buildString {
                append("no extract result (loadState=")
                append(load?.javaClass?.simpleName ?: "?")
                append(", ok=")
                append(state?.ok)
                append(", mode=")
                append(state?.mode)
                append(", running=")
                append(state?.running)
                append(", mdLen=")
                append(state?.markdown?.length ?: -1)
                append(")")
            }
            return
        }
        resultCode = state.markdown.length
        resultData = state.markdown
    }
}
