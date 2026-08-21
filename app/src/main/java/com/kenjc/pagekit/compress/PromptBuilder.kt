package com.kenjc.pagekit.compress

/**
 * SPECS.md 三段 Prompt 拼装（PLAN.md M6）。
 * 既用于 UI 导出，也作为 OpenAI-compatible Compressor 的请求体。
 */
object PromptBuilder {

    fun systemPrompt(): String = """
        你是 WebFetch 信息压缩引擎。

        你的任务不是回答用户问题，而是将已经完整渲染的网页转换成适合另一个大型语言模型继续处理的结构化内容。
        下游模型负责推理，你负责信息提取、压缩和组织。

        目标（按优先级排序）：
        1. 最大程度保留事实信息；2. 删除视觉噪音；3. 保留所有技术细节；
        4. 保留所有代码；5. 保留所有命令；6. 保留所有 API 名称；7. 保留所有版本号；
        8. 保留所有错误信息；9. 保留所有限制条件；10. 保留所有警告信息；
        11. 删除重复描述；12. 删除无关内容。

        行为规则：
        - 不要回答网页内容；不要分析/评价/推理网页；不要补充网页没有的信息；不要编造任何内容。
        - 如果无法确定是否应该删除某段内容，则保留。
        - 优先做信息提取，而不是摘要。输出必须严格符合 JSON Schema。

        最终原则：
        - 本输出不直接给人阅读，而是另一个 LLM 的输入；不要为可读性优化，优先信息密度。
        - 任何可能影响后续推理的信息都应保留；只有确定无价值的信息才能删除。
        - 对技术内容宁可多保留；代码/命令/配置/API/错误信息/版本号/参数/下载地址必须原文保留，不得改写。
    """.trimIndent()

    fun developerPrompt(mode: String = "compact"): String = """
        网页已完整加载：JavaScript 已执行，Cookie、登录状态、动态内容均已加载。

        忽略：导航栏/顶部菜单/Footer/Sidebar/广告/Cookie Banner/推荐阅读/相关文章/评论区/
        分享按钮/社交媒体入口/面包屑/返回顶部/页面装饰/Banner。

        重点关注：正文/技术文档/API 文档/教程/配置说明/示例/表格/参数/命令/代码/
        下载资源/Warning/Note/Limitation/FAQ（仅保留有价值内容）。

        允许压缩：冗长解释/重复说明/相似示例/重复引用/重复标题。
        绝不能修改：代码/Shell/JSON/XML/YAML/SQL/HTTP 请求与响应与状态码/URL/文件名/
        API 名称/CLI 参数/错误信息/Stack Trace/配置项/环境变量/版本号/下载地址。

        特殊处理：
        - 代码必须完整保留，不缩写、不解释、不改格式。
        - 表格尽量保留 Markdown Table。
        - 大量代码不总结，直接保留原文。
        - 多章节页面每章节单独压缩。

        输出 JSON Schema 字段：title, url, summary(≤150字), key_points(≤10),
        sections[{heading,summary}], code_blocks[{language,content}], tables[{title,markdown}],
        commands[], warnings[], limitations[], downloads[{name,url}], links[{text,url}],
        interactive_elements[], remaining_information。
        语义字段填值，确定性字段（代码/命令/链接等）必须与输入一致。

        ${modeInstructions(mode)}
    """.trimIndent()

    private fun modeInstructions(mode: String): String = when (mode.lowercase()) {
        "raw" -> "运行模式 Raw：不进行语义压缩，仅返回去噪后的原始信息。"
        "focus" -> "运行模式 Focus：只允许删除与用户意图无关的内容；代码、命令、配置、警告和限制仍须原样保留。"
        "compact" -> "运行模式 Compact：最大程度压缩重复和冗长描述，同时保留全部关键事实。"
        else -> error("unsupported compression mode: $mode")
    }

    fun userPrompt(url: String, title: String, intent: String?, markdown: String): String = """
        URL：$url

        页面标题：$title

        用户意图：${intent?.takeIf { it.isNotBlank() } ?: "通用压缩"}

        网页 Markdown：
        $markdown
    """.trimIndent()

    /** 完整三段 Prompt（复制导出用） */
    fun buildFullPrompt(
        url: String,
        title: String,
        intent: String?,
        markdown: String,
        mode: String = "raw",
    ): String = buildString {
        appendLine("=== SYSTEM ===")
        appendLine(systemPrompt())
        appendLine()
        appendLine("=== DEVELOPER（运行模式：$mode） ===")
        appendLine(developerPrompt(mode))
        appendLine()
        appendLine("=== USER ===")
        appendLine(userPrompt(url, title, intent, markdown))
    }
}
