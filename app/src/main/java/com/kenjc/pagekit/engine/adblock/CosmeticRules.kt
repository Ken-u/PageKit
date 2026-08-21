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

    fun selectorsFor(host: String): List<String> {
        val normalized = normalizeHost(host)
        if (normalized.isEmpty()) return emptyList()
        val selected = LinkedHashSet(generic)
        scoped.asSequence().filter { it.appliesTo(normalized) }.forEach { selected += it.selector }
        exceptions.asSequence().filter { it.appliesTo(normalized) }.forEach { selected -= it.selector }
        return selected.toList()
    }

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
