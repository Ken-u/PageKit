package com.kenjc.pagekit.engine.adblock

data class CosmeticParseStats(
    val accepted: Int,
    val skippedUnsupported: Int,
)

data class CosmeticRuleSet(
    private val generic: List<String>,
    private val scoped: List<Rule>,
    private val exceptions: List<Rule>,
    val stats: CosmeticParseStats,
) {
    data class Rule(
        val includedDomains: Set<String>,
        val excludedDomains: Set<String>,
        val selector: String,
    ) {
        fun appliesTo(host: String): Boolean {
            val included = includedDomains.isEmpty() || includedDomains.matchesHostOrParent(host)
            return included && !excludedDomains.matchesHostOrParent(host)
        }
    }

    /**
     * 域名索引：included/excluded 域名 → 规则。查询时只对 host 及其父域做哈希查找，
     * 匹配成本从 O(全部 scoped 规则) 降到 O(域名层级 + 该域规则数)。
     * includedDomains 为空的规则（仅含 ~exclusion 的 scoped）仍需全量兜底扫描，单独立桶。
     */
    private val scopedIndex: Map<String, List<Rule>> = scoped
        .filter { it.includedDomains.isNotEmpty() }
        .flatMap { rule -> rule.includedDomains.map { it to rule } }
        .groupBy({ it.first }, { it.second })

    private val scopedNoInclude: List<Rule> = scoped.filter { it.includedDomains.isEmpty() }

    private val exceptionIndex: Map<String, List<Rule>> = exceptions
        .filter { it.includedDomains.isNotEmpty() }
        .flatMap { rule -> rule.includedDomains.map { it to rule } }
        .groupBy({ it.first }, { it.second })

    private val exceptionNoInclude: List<Rule> = exceptions.filter { it.includedDomains.isEmpty() }

    /** host 及其父域（如 a.b.com → [a.b.com, b.com, com]），供逐级查索引。 */
    private fun hostAndParents(host: String): Sequence<String> = sequence {
        var current = host
        while (true) {
            yield(current)
            val dot = current.indexOf('.')
            if (dot < 0) return@sequence
            current = current.substring(dot + 1)
        }
    }

    /** 索引命中 + 无正向域名规则的线性兜底；保持与全量扫描完全相同的结果集。 */
    private fun candidatesFrom(index: Map<String, List<Rule>>, noInclude: List<Rule>, normalized: String): Sequence<Rule> =
        hostAndParents(normalized)
            .flatMap { index[it].orEmpty().asSequence() }
            .plus(noInclude.asSequence())
            .filter { it.appliesTo(normalized) }

    fun selectorsFor(host: String): List<String> {
        val normalized = normalizeHost(host)
        if (normalized.isEmpty()) return emptyList()
        val selected = LinkedHashSet(generic)
        candidatesFrom(scopedIndex, scopedNoInclude, normalized).forEach { selected += it.selector }
        candidatesFrom(exceptionIndex, exceptionNoInclude, normalized).forEach { selected -= it.selector }
        return selected.toList()
    }

    /** 仅供快照序列化使用：原始规则分桶（generic/scoped/exceptions）。 */
    val forSnapshot: Triple<List<String>, List<Rule>, List<Rule>>
        get() = Triple(generic, scoped, exceptions)

    companion object {
        val EMPTY = CosmeticRuleSet(emptyList(), emptyList(), emptyList(), CosmeticParseStats(0, 0))
    }
}

/** EasyList/ABP 基础元素隐藏语法解析器。 */
object CosmeticRuleParser {

    fun parse(texts: Iterable<String>): CosmeticRuleSet {
        val generic = LinkedHashSet<String>()
        val scoped = ArrayList<CosmeticRuleSet.Rule>()
        val exceptions = ArrayList<CosmeticRuleSet.Rule>()
        var accepted = 0
        var skipped = 0

        texts.forEach { text ->
            text.lineSequence().forEach lines@{ raw ->
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith('!') || line.startsWith('[')) return@lines

                val exceptionIndex = line.indexOf("#@#")
                val hideIndex = line.indexOf("##")
                val isException = exceptionIndex >= 0
                val separatorIndex = if (isException) exceptionIndex else hideIndex
                val separatorLength = if (isException) 3 else 2
                if (separatorIndex < 0) return@lines

                val selector = line.substring(separatorIndex + separatorLength).trim()
                if (!selector.isSupportedSelector()) {
                    skipped++
                    return@lines
                }

                val domainSpec = parseDomains(line.substring(0, separatorIndex))
                if (domainSpec == null) {
                    skipped++
                    return@lines
                }
                val rule = CosmeticRuleSet.Rule(domainSpec.first, domainSpec.second, selector)
                when {
                    isException -> exceptions += rule
                    rule.includedDomains.isEmpty() && rule.excludedDomains.isEmpty() -> generic += selector
                    else -> scoped += rule
                }
                accepted++
            }
        }
        return CosmeticRuleSet(generic.toList(), scoped, exceptions, CosmeticParseStats(accepted, skipped))
    }

    /** 返回 included/excluded；有正向域名但全部无效时跳过整条，避免误变成全局规则。 */
    private fun parseDomains(raw: String): Pair<Set<String>, Set<String>>? {
        if (raw.isBlank()) return emptySet<String>() to emptySet()
        val included = LinkedHashSet<String>()
        val excluded = LinkedHashSet<String>()
        var positiveTokens = 0
        raw.split(',').forEach { tokenValue ->
            val token = tokenValue.trim()
            val negative = token.startsWith('~')
            val value = token.removePrefix("~")
            if (!negative) positiveTokens++
            if (value.contains('*') || value.contains('/') || value.contains('^')) return@forEach
            val domain = normalizeHost(value)
            if (domain.isEmpty() || !domain.contains('.')) return@forEach
            if (negative) excluded += domain else included += domain
        }
        if (positiveTokens > 0 && included.isEmpty()) return null
        return included to excluded
    }

    private fun String.isSupportedSelector(): Boolean {
        if (isBlank() || length > 1024 || any { it == '\n' || it == '\r' || it.code < 0x20 }) return false
        val lower = lowercase()
        return sequenceOf(
            "+js(",
            ":has(",
            ":has-text(",
            ":matches-css(",
            ":matches-path(",
            ":xpath(",
            ":upward(",
            ":remove(",
            ":style(",
            ":-abp-",
            "{",
            "}",
        ).none(lower::contains)
    }
}
