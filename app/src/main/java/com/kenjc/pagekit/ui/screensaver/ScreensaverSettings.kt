package com.kenjc.pagekit.ui.screensaver

import android.content.Context

/** 应用运行设置的持久化：屏保闲置时长、每 profile 最大并发 session 数。 */
class ScreensaverSettings(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun load(): Long = preferences.getLong(KEY_IDLE_TIMEOUT_MS, DEFAULT_IDLE_TIMEOUT_MS)

    fun save(value: Long) {
        preferences.edit().putLong(KEY_IDLE_TIMEOUT_MS, value).apply()
    }

    fun loadMaxSessions(): Int = preferences.getInt(KEY_MAX_SESSIONS, DEFAULT_MAX_SESSIONS)

    fun saveMaxSessions(value: Int) {
        require(value in 1..8) { "maxSessions must be between 1 and 8" }
        preferences.edit().putInt(KEY_MAX_SESSIONS, value).apply()
    }

    companion object {
        const val DEFAULT_IDLE_TIMEOUT_MS = 120_000L
        const val TIMEOUT_DISABLED = 0L
        const val DEFAULT_MAX_SESSIONS = 4
        private const val PREFERENCES = "pagekit_screensaver"
        private const val KEY_IDLE_TIMEOUT_MS = "idle_timeout_ms"
        private const val KEY_MAX_SESSIONS = "max_sessions"
    }
}
