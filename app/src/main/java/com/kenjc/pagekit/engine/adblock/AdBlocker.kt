package com.kenjc.pagekit.engine.adblock

import android.webkit.WebResourceRequest

/**
 * 去广告 SPI（PLAN.md：V1 冻结接口形状，V2 挂开源规则库）。
 *
 * 双轨语义（对齐 uBlock 类工具）：
 * - 请求级：[shouldBlock] 供 WebViewClient.shouldInterceptRequest 查询（hosts 域名黑名单）
 * - DOM 级：[elementHidingRules] 输出元素隐藏选择器，并入 NoiseFilter
 */
interface AdBlocker {

    /** 请求级拦截：true 表示以空响应拦截该请求 */
    fun shouldBlock(request: WebResourceRequest): Boolean

    /** DOM 级元素隐藏选择器；参数用于解析 EasyList 的域名限定与例外规则。 */
    fun elementHidingRules(pageUrl: String): List<String> = emptyList()
}

/** V1 默认实现：全放行（去噪由 NoiseFilter 内置广告选择器承担） */
object NoopAdBlocker : AdBlocker {
    override fun shouldBlock(request: WebResourceRequest): Boolean = false
}
