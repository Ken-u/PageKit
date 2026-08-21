package com.kenjc.pagekit.api

import com.kenjc.pagekit.api.dto.CompressedPage
import com.kenjc.pagekit.api.dto.FetchRequest
import com.kenjc.pagekit.api.dto.ExpandedSection
import com.kenjc.pagekit.engine.SearchEngine

/**
 * 单个 PageKit 浏览器 Session 的对外门面；多 Session/Profile 由 session gateway 路由。
 */
interface PageKitApi {

    /** 加载页面（返回最终 URL；失败抛异常或经状态流暴露） */
    suspend fun fetch(request: FetchRequest): CompressedPage

    /** 展开最近一次或指定 page_id 的原始 Markdown 章节，无需重新抓页。 */
    suspend fun expand(section: String, pageId: String? = null): ExpandedSection

    /** 列举当前页面可交互元素（M5 实现） */
    suspend fun listInteractiveElements(): List<String>

    /** 搜索：拼接引擎结果页 URL 并导航（对应 MCP websearch 工具；engine 见 SearchEngine.names） */
    suspend fun webSearch(query: String, engine: String = SearchEngine.DEFAULT): CompressedPage

    /** 浏览器控制（M5 实现） */
    suspend fun click(elementId: String): Boolean
    suspend fun type(elementId: String, text: String): Boolean
    suspend fun scroll(dx: Int = 0, dy: Int = 600): Boolean
}
