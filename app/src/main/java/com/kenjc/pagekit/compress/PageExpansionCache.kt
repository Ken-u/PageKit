package com.kenjc.pagekit.compress

import android.content.Context
import android.util.AtomicFile
import com.kenjc.pagekit.api.dto.ExpandedSection
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class CachedPage(
    val pageId: String,
    val url: String,
    val title: String,
    val sections: List<CachedSection>,
    val createdAtEpochMs: Long,
)

@Serializable
data class CachedSection(
    val sectionId: String,
    val heading: String,
    val markdown: String,
)

interface PageExpansionCache {
    fun store(url: String, title: String, markdown: String): CachedPage
    fun expand(pageId: String?, section: String): ExpandedSection
}

/** Markdown 标题感知切片；忽略 fenced code 内形似标题的文本。 */
object MarkdownSectionParser {
    private val headingPattern = Regex("^(#{1,6})\\s+(.+?)\\s*#*\\s*$")
    private val fencePattern = Regex("^\\s*(`{3,}|~{3,}).*$")
    private val setextUnderlinePattern = Regex("^\\s*(=+|-+)\\s*$")

    fun parse(title: String, markdown: String): List<CachedSection> {
        val sections = mutableListOf<Pair<String, StringBuilder>>()
        var currentHeading = title.ifBlank { "正文" }
        var current = StringBuilder()
        var fence: Char? = null
        var fenceLength = 0

        fun flush() {
            val content = current.toString().trim()
            if (content.isNotEmpty()) sections += currentHeading to StringBuilder(content)
            current = StringBuilder()
        }

        val lines = markdown.lines()
        var index = 0
        while (index < lines.size) {
            val line = lines[index]
            val fenceMatch = fencePattern.matchEntire(line)
            if (fenceMatch != null) {
                val marker = fenceMatch.groupValues[1]
                if (fence == null) {
                    fence = marker.first()
                    fenceLength = marker.length
                } else if (marker.first() == fence && marker.length >= fenceLength) {
                    fence = null
                    fenceLength = 0
                }
                current.appendLine(line)
                index++
                continue
            }
            val setextHeading = if (
                fence == null &&
                line.isNotBlank() &&
                index + 1 < lines.size &&
                setextUnderlinePattern.matches(lines[index + 1])
            ) line.trim() else null
            if (setextHeading != null) {
                flush()
                currentHeading = setextHeading
                current.appendLine(line)
                current.appendLine(lines[index + 1])
                index += 2
                continue
            }
            val heading = if (fence == null) headingPattern.matchEntire(line) else null
            if (heading != null) {
                flush()
                currentHeading = heading.groupValues[2].trim()
                current.appendLine(line)
            } else {
                current.appendLine(line)
            }
            index++
        }
        flush()
        if (sections.isEmpty() && markdown.isNotBlank()) {
            sections += currentHeading to StringBuilder(markdown.trim())
        }
        return sections.mapIndexed { index, (heading, content) ->
            CachedSection("s${index + 1}", heading, content.toString())
        }
    }
}

class FilePageExpansionCache(
    context: Context,
    private val maxPages: Int = 12,
    namespace: String = "default",
) : PageExpansionCache {
    private val safeNamespace = namespace.also {
        require(NAMESPACE.matches(it)) { "invalid page cache namespace" }
    }
    private val directory = context.filesDir.resolve(
        if (safeNamespace == "default") "page-cache" else "page-cache/$safeNamespace",
    ).apply { mkdirs() }
    private val preferences = context.getSharedPreferences(
        if (safeNamespace == "default") "pagekit_page_cache" else "pagekit_page_cache_$safeNamespace",
        Context.MODE_PRIVATE,
    )
    private val latestId = AtomicReference(preferences.getString("latest_page_id", null))
    private val json = Json { ignoreUnknownKeys = true }

    override fun store(url: String, title: String, markdown: String): CachedPage {
        require(markdown.length <= MAX_MARKDOWN_CHARS) {
            "page Markdown is too large for expansion cache"
        }
        val pageId = pageId(url, markdown)
        val page = CachedPage(
            pageId = pageId,
            url = url,
            title = title,
            sections = MarkdownSectionParser.parse(title, markdown),
            createdAtEpochMs = System.currentTimeMillis(),
        )
        val atomic = AtomicFile(fileFor(pageId))
        val output = atomic.startWrite()
        try {
            output.write(json.encodeToString(page).toByteArray(Charsets.UTF_8))
            atomic.finishWrite(output)
        } catch (error: Throwable) {
            atomic.failWrite(output)
            throw error
        }
        latestId.set(pageId)
        preferences.edit().putString("latest_page_id", pageId).apply()
        prune()
        return page
    }

    override fun expand(pageId: String?, section: String): ExpandedSection {
        val resolvedPageId = pageId?.takeIf(String::isNotBlank) ?: latestId.get()
            ?: error("no cached page; call webfetch first")
        require(PAGE_ID.matches(resolvedPageId)) { "invalid page_id" }
        val file = fileFor(resolvedPageId)
        require(file.isFile) { "cached page not found: $resolvedPageId" }
        val page = json.decodeFromString<CachedPage>(file.readText())
        val key = section.trim()
        require(key.isNotEmpty()) { "section is required" }
        val matches = page.sections.filter {
            it.sectionId.equals(key, ignoreCase = true) || it.heading.equals(key, ignoreCase = true)
        }.ifEmpty {
            page.sections.filter { it.heading.contains(key, ignoreCase = true) }
        }
        require(matches.size == 1) {
            if (matches.isEmpty()) {
                "section not found; available: ${page.sections.joinToString { "${it.sectionId}:${it.heading}" }}"
            } else {
                "ambiguous section; use section_id: ${matches.joinToString { it.sectionId }}"
            }
        }
        val found = matches.single()
        file.setLastModified(System.currentTimeMillis())
        return ExpandedSection(resolvedPageId, found.sectionId, found.heading, found.markdown)
    }

    private fun fileFor(pageId: String) = directory.resolve("$pageId.json")

    private fun prune() {
        directory.listFiles { file -> file.isFile && file.name.matches(Regex("[a-f0-9]{24}\\.json")) }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(maxPages)
            ?.forEach { it.delete() }
    }

    private fun pageId(url: String, markdown: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$url\u0000$markdown".toByteArray(Charsets.UTF_8))
        return digest.take(12).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private companion object {
        val PAGE_ID = Regex("[a-f0-9]{24}")
        val NAMESPACE = Regex("[a-zA-Z0-9._-]{1,80}")
        const val MAX_MARKDOWN_CHARS = 5_000_000
    }
}
