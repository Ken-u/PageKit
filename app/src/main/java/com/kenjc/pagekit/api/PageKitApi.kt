package com.kenjc.pagekit.api

import com.kenjc.pagekit.api.dto.CompressedPage
import com.kenjc.pagekit.api.dto.FetchRequest
import com.kenjc.pagekit.engine.SearchEngine

/**
 * PageKit 对外门面（PLAN.md：UI 与未来 MCP server 共用的契约层）。
 * V1：UI 为首个客户端；V2 MCP server 调用同一门面。
 */
interface PageKitApi {

    /** 加载页面（返回最终 URL；失败抛异常或经状态流暴露） */
    suspend fun fetch(request: FetchRequest): CompressedPage

    /** V1 未实现：章节缓存 + 展开（V2 依赖 LLM 语义压缩） */
    suspend fun expand(section: String): String = throw NotImplementedError("expand() 计划于 V2 提供")

    /** 列举当前页面可交互元素（M5 实现） */
    suspend fun listInteractiveElements(): List<String>

    /** 搜索：拼接引擎结果页 URL 并导航（对应 MCP websearch 工具；engine 见 SearchEngine.names） */
    suspend fun webSearch(query: String, engine: String = SearchEngine.DEFAULT): CompressedPage

    /** 浏览器控制（M5 实现） */
    suspend fun click(elementId: String): Boolean
    suspend fun type(elementId: String, text: String): Boolean
    suspend fun scroll(dx: Int = 0, dy: Int = 600): Boolean
}
