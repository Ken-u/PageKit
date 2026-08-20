package com.kenjc.pagekit.compress

import com.kenjc.pagekit.api.dto.CompressedPage
import com.kenjc.pagekit.api.dto.FetchRequest

/**
 * 压缩引擎接口（PLAN.md M6 / SPECS.md 压缩引擎）：
 * 输入页面上下文（url/title/markdown/intent/mode），输出符合 Schema 的 CompressedPage。
 * V1 只有 NoopCompressor（透传规则装配结果）；V2 接 LLM。
 */
interface Compressor {

    /** 模型显示名（调试/导出用） */
    val name: String

    suspend fun compress(request: FetchRequest, context: PageContext): CompressedPage
}

/** 页面上下文：管线产出，喂给压缩器 */
data class PageContext(
    val title: String,
    val markdown: String,
    val structured: CompressedPage,
)
