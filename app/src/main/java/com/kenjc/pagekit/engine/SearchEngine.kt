package com.kenjc.pagekit.engine

/**
 * 搜索引擎注册表：引擎名 → 结果页 URL 模板（URL 模板即稳定契约）。
 * 默认 Bing（广告少、结果干净）；支持扩展自定义模板。
 */
object SearchEngine {

    /** 已知引擎的结果页 URL 模板，{q} 为查询词占位符 */
    private val templates = linkedMapOf(
        "bing" to "https://www.bing.com/search?q={q}",
        "baidu" to "https://www.baidu.com/s?wd={q}",
        "sogou" to "https://www.sogou.com/web?query={q}",
        "360" to "https://www.so.com/s?q={q}",
        "google" to "https://www.google.com/search?q={q}",
    )

    val DEFAULT = "bing"
    val names: List<String> get() = templates.keys.toList()

    /** 注册自定义引擎（如站内搜索：addEngine("zhihu", "https://www.zhihu.com/search?q={q}")） */
    fun addEngine(name: String, urlTemplate: String) {
        templates[name.lowercase()] = urlTemplate
    }

    /** 拼接结果页 URL；未注册引擎返回 null */
    fun buildResultUrl(engine: String, query: String): String? =
        templates[engine.lowercase()]
            ?.takeIf { it.contains("{q}") }
            ?.replace("{q}", encode(query))

    /** 对引擎名/URL 做宽松解析（"Bing"、"https://www.bing.com/" 均可） */
    fun resolve(engineOrUrl: String): String? {
        val t = engineOrUrl.trim().lowercase()
        templates.keys.firstOrNull { t.contains(it) }?.let { return it }
        return null
    }

    private fun encode(query: String): String =
        java.net.URLEncoder.encode(query, "UTF-8")
            .replace("+", "%20")
}
