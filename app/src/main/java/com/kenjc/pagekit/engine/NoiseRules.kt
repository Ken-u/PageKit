package com.kenjc.pagekit.engine

/**
 * SPECS.md「忽略以下内容」清单 → CSS 选择器规则表。
 * 用于主内容提取前的 DOM 清洗（在 document 克隆上执行，不影响页面本身）。
 * V2 起与 AdBlocker.elementHidingRules() 合并生效。
 */
object NoiseRules {

    /** SPECS.md 忽略清单：导航/菜单/Footer/Sidebar/广告/Cookie/推荐/评论/分享/社交/面包屑/装饰 */
    val SELECTORS: List<String> = listOf(
        // 语义标签
        "nav", "header", "footer", "aside", "form", "iframe", "noscript", "svg", "canvas",
        "[role=navigation]", "[role=banner]", "[role=complementary]", "[role=contentinfo]", "[role=search]",
        // 广告 / Banner
        ".ad", ".ads", ".advert", ".advertisement", ".ad-slot", ".ad-container", ".banner",
        // 导航 / 菜单 / 面包屑 / 分页
        ".nav", ".navbar", ".navigation", ".menu", ".top-menu", ".main-menu", ".breadcrumb",
        ".breadcrumbs", ".pagination", ".pager",
        // Sidebar / 页面装饰
        ".sidebar", ".side-bar", ".widget",
        // Footer / 返回顶部
        ".footer", ".site-footer", ".back-to-top", ".goto-top",
        // Cookie / 弹窗
        ".cookie", ".cookies", ".cookie-banner", ".cookie-consent", ".popup", ".modal", ".overlay",
        // 推荐 / 相关 / 社交 / 分享 / 评论区
        ".related", ".related-posts", ".related-articles", ".recommend", ".recommendation",
        ".share", ".sharing", ".social", ".social-media", ".social-links",
        ".comment", ".comments", ".comment-section", "#comment", "#comments", "#disqus_thread",
        // id 变体
        "#nav", "#navbar", "#navigation", "#menu", "#sidebar", "#footer", "#header", "#banner",
    )

    /** 生成注入 JS：在给定根元素上删除全部噪声节点 */
    fun toJs(rootRef: String): String = buildString {
        append("(function(){var n=")
        append(SELECTORS.joinToString(prefix = "[", postfix = "]") { "\"$it\"" })
        append(";n.forEach(function(s){try{")
        append(rootRef)
        append(".querySelectorAll(s).forEach(function(el){el.remove()})}catch(e){}});})()")
    }
}
