package com.kenjc.pagekit.net

import android.content.Context

/**
 * WebView 代理配置。仅作用于 WebView 网络栈（ProxyController.setProxyOverride），
 * 不影响全局系统网络或其他 App。
 *
 * @param enabled 是否启用代理
 * @param host    代理服务器地址（如 "127.0.0.1"）
 * @param port    代理端口
 * @param bypass  不走代理的主机/域名列表（分号分隔），如 "localhost;10.0.0.0/8;*.local"
 */
data class ProxyConfig(
    val enabled: Boolean = false,
    val host: String = "",
    val port: Int = 0,
    val bypass: String = "",
) {
    /** 配置有效且已启用 */
    val active: Boolean get() = enabled && host.isNotBlank() && port in 1..65535

    /** 解析 bypass 字符串为 ProxyController 所需的分号分隔列表 */
    fun bypassList(): List<String> =
        bypass.split(';', ',', '\n').map { it.trim() }.filter { it.isNotEmpty() }
}

interface ProxySettings {
    fun load(): ProxyConfig
    fun save(config: ProxyConfig)
}

class SharedPreferencesProxySettings(context: Context) : ProxySettings {
    @Suppress("DEPRECATION")
    private val preferences = context.getSharedPreferences(
        "pagekit_proxy",
        Context.MODE_PRIVATE or Context.MODE_MULTI_PROCESS,
    )

    override fun load() = ProxyConfig(
        enabled = preferences.getBoolean(KEY_ENABLED, false),
        host = preferences.getString(KEY_HOST, "").orEmpty(),
        port = preferences.getInt(KEY_PORT, 0),
        bypass = preferences.getString(KEY_BYPASS, "").orEmpty(),
    )

    override fun save(config: ProxyConfig) {
        check(
            preferences.edit()
                .putBoolean(KEY_ENABLED, config.enabled)
                .putString(KEY_HOST, config.host.trim())
                .putInt(KEY_PORT, config.port)
                .putString(KEY_BYPASS, config.bypass.trim())
                .commit(),
        ) { "failed to persist proxy settings" }
    }

    private companion object {
        const val KEY_ENABLED = "enabled"
        const val KEY_HOST = "host"
        const val KEY_PORT = "port"
        const val KEY_BYPASS = "bypass"
    }
}
