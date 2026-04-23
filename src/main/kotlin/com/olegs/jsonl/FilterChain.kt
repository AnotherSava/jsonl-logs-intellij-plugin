package com.olegs.jsonl

/**
 * Predicate over a parsed [LogEntry]. Most predicates only look at the raw
 * entry; [TextFilter] also sees the rendered formatted text (since text-filter
 * matching is specified to consider either representation).
 */
interface EntryPredicate {
    fun accepts(entry: LogEntry, formatted: String): Boolean
}

/**
 * Composes several predicates with AND semantics. [isActive] is true when at
 * least one real predicate is present — callers use it to decide whether to
 * engage the filtered-raw viewer / recompute stats from the filtered subset.
 */
class FilterChain(predicates: List<EntryPredicate>) {
    private val ps: List<EntryPredicate> = predicates.filterNot { it is AlwaysAccept }
    val isActive: Boolean = ps.isNotEmpty()

    fun accepts(entry: LogEntry, formatted: String): Boolean = ps.all { it.accepts(entry, formatted) }

    private object AlwaysAccept : EntryPredicate {
        override fun accepts(entry: LogEntry, formatted: String): Boolean = true
    }
}

/** Drops blank lines. Always present as the first predicate so the rest can
 *  assume a non-blank, JSON-shaped entry when they care. */
object NonBlankPredicate : EntryPredicate {
    override fun accepts(entry: LogEntry, formatted: String): Boolean = !entry.isBlank
}

class SeverityPredicate(private val min: Severity) : EntryPredicate {
    override fun accepts(entry: LogEntry, formatted: String): Boolean {
        if (min == Severity.ALL) return true
        val level = entry.level ?: return false
        return Severity.rankOf(level) >= min.rank
    }
}

class TargetPredicate(private val target: String) : EntryPredicate {
    override fun accepts(entry: LogEntry, formatted: String): Boolean {
        if (target.isEmpty()) return true
        val t = entry.target ?: return false
        return t == target
    }
}

class TextPredicate(private val text: String) : EntryPredicate {
    override fun accepts(entry: LogEntry, formatted: String): Boolean {
        if (text.isEmpty()) return true
        return entry.trimmed.contains(text, ignoreCase = true) ||
            formatted.contains(text, ignoreCase = true)
    }
}
