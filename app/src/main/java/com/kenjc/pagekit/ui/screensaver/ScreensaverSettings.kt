package com.kenjc.pagekit.ui.screensaver

import android.content.Context

/** 屏保设置的持久化：闲置多少毫秒后进入屏保；0 表示关闭闲置自动进入。 */
class ScreensaverSettings(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun load(): Long = preferences.getLong(KEY_IDLE_TIMEOUT_MS, DEFAULT_IDLE_TIMEOUT_MS)

    fun save(value: Long) {
        preferences.edit().putLong(KEY_IDLE_TIMEOUT_MS, value).apply()
    }

    companion object {
        const val DEFAULT_IDLE_TIMEOUT_MS = 120_000L
        const val TIMEOUT_DISABLED = 0L
        private const val PREFERENCES = "pagekit_screensaver"
        private const val KEY_IDLE_TIMEOUT_MS = "idle_timeout_ms"
    }
}
