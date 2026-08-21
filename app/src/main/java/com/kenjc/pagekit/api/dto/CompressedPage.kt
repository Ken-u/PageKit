package com.kenjc.pagekit.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * SPECS.md「输出 JSON Schema」的 Kotlin 映射。
 * raw 模式语义字段可为 null；compact/focus 由 LLM 填充，本地确定性字段始终覆盖模型结果。
 * 本 DTO 序列化结果即 MCP webfetch 工具的 page payload。
 */
@Serializable
data class CompressedPage(
    val page_id: String? = null,
    val title: String = "",
    val url: String = "",
    val summary: String? = null,
    val key_points: List<String>? = null,
    val sections: List<Section>? = null,
    val code_blocks: List<CodeBlock>? = null,
    val tables: List<Table>? = null,
    val commands: List<String>? = null,
    val warnings: List<String>? = null,
    val limitations: List<String>? = null,
    val downloads: List<Download>? = null,
    val links: List<Link>? = null,
    val interactive_elements: List<String>? = null,
    val remaining_information: String? = null,
) {
    @Serializable
    data class Section(
        val heading: String,
        val summary: String,
    )

    @Serializable
    data class CodeBlock(
        val language: String,
        val content: String,
    )

    @Serializable
    data class Table(
        val title: String,
        val markdown: String,
    )

    @Serializable
    data class Download(
        val name: String,
        val url: String,
    )

    @Serializable
    data class Link(
        val text: String,
        val url: String,
    )
}

/** webfetch 请求参数（MCP webfetch 工具签名） */
@Serializable
data class FetchRequest(
    val url: String,
    val intent: String? = null,
    val mode: String = "raw", // raw | compact | focus
)

@Serializable
data class ExpandedSection(
    val page_id: String,
    val section_id: String,
    val heading: String,
    val markdown: String,
)
