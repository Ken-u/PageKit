package com.kenjc.pagekit.engine

import com.vladsch.flexmark.html2md.converter.FlexmarkHtmlConverter

/**
 * HTML → Markdown 转换（PLAN.md M3）。
 * flexmark html2md：表格/代码块/链接支持良好；SPECS.md 要求代码、命令、URL 等原文保留，
 * 故不做任何字符级改写，仅结构转换。
 */
object HtmlToMarkdown {

    fun convert(html: String): String = runCatching {
        FlexmarkHtmlConverter.builder().build().convert(html).trim()
    }.getOrDefault("")
}
