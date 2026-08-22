package com.kenjc.pagekit.mcp

import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * MCP HTTP 服务的绑定模式，可通过启动 Intent extra `mcp_bind` 指定：
 *
 * - [ALL]：绑定 `0.0.0.0`（默认，行为与历史版本一致）。回环、局域网乃至热点客户端均可访问，
 *   安全完全依赖 Bearer token + Origin 策略，绑定地址本身不提供隔离。
 * - [LAN]：仅绑定设备局域网 IPv4（通常是 wlan0），本机回环 `127.0.0.1` 不再可达
 *   （`adb forward tcp:3000` 会失效，需从局域网 IP 直连）。
 * - [LOOPBACK]：仅绑定 `127.0.0.1`，仅供本机 / `adb forward` 使用。
 */
enum class McpBindMode {
    ALL,
    LAN,
    LOOPBACK;

    companion object {
        /** 解析 `mcp_bind` extra；null / 空串 / 未知值返回 null（调用方走默认 [ALL]）。 */
        fun parse(value: String?): McpBindMode? = when (value?.trim()?.lowercase()) {
            null, "" -> null
            "all", "any", "0.0.0.0" -> ALL
            "lan", "wifi" -> LAN
            "loopback", "localhost", "127.0.0.1" -> LOOPBACK
            else -> null
        }
    }
}

/** 将 [McpBindMode] 解析为实际绑定的 host。 */
object McpBindHosts {

    const val ALL_HOST = "0.0.0.0"
    const val LOOPBACK_HOST = "127.0.0.1"

    fun resolve(mode: McpBindMode): String = when (mode) {
        McpBindMode.ALL -> ALL_HOST
        McpBindMode.LOOPBACK -> LOOPBACK_HOST
        // 无可用局域网 IPv4（未连 Wi-Fi 等）时退回回环地址，避免把服务暴露到全部接口。
        McpBindMode.LAN -> selectLanHost(deviceIpv4Addresses()) ?: LOOPBACK_HOST
    }

    /**
     * 从候选地址中选出局域网 IPv4：跳过 IPv6 与回环，优先 site-local
     * （10/8、172.16/12、192.168/16）地址，其次任意全局 IPv4。
     */
    internal fun selectLanHost(addresses: List<InetAddress>): String? =
        addresses
            .asSequence()
            .filterIsInstance<Inet4Address>()
            .filterNot { it.isLoopbackAddress }
            .sortedBy { if (it.isSiteLocalAddress) 0 else 1 }
            .firstOrNull()
            ?.hostAddress

    private fun deviceIpv4Addresses(): List<InetAddress> = runCatching {
        buildList {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return@buildList
            for (nic in interfaces) {
                if (!nic.isUp || nic.isLoopback) continue
                for (address in nic.inetAddresses) {
                    add(address)
                }
            }
        }
    }.getOrDefault(emptyList())
}
