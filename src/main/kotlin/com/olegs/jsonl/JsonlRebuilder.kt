package com.olegs.jsonl

import java.time.Instant

/**
 * Full rebuild output: everything the editor needs to refresh documents + stats
 * + toolbars. A plain data record so it can be produced and tested without Swing.
 */
data class RebuildResult(
    val structuredLines: List<FormattedLine>,
    /** Raw lines that survived the filter (all of them when no filter is active).
     *  Fed into the synthetic raw viewer, which is now the only raw pane. */
    val rawLines: List<String>,
    /** index into the filtered-formatted list → raw line index in the original source. */
    val formattedToRawLine: List<Int>,
    val stats: JsonlStats,
    /** Every distinct `target` field value seen in the source, regardless of filter. */
    val allTargets: List<String>,
)

/**
 * Stateless rebuild of the formatted + filtered-raw views. Parses each line
 * exactly once, applies the filter chain, computes common prefix + alignment
 * widths from the surviving subset, and formats.
 */
object JsonlRebuilder {

    fun rebuild(source: String, session: JsonlSession, settings: JsonlSettings.State): RebuildResult {
        val fieldMapping = settings.fieldMapping()
        val entries = source.split('\n').map { LogEntryParser.parse(it, fieldMapping) }

        val allTargets = entries.mapNotNullTo(sortedSetOf()) { it.target }.toList()

        val userFiltering = session.isFilterActive()
        val filters = FilterChain(
            if (userFiltering) listOf(
                NonBlankPredicate,
                SeverityPredicate(session.minSeverity),
                TargetPredicate(session.targetFilter),
                TextPredicate(session.textFilter),
            ) else emptyList()
        )

        // Draft formatter: used only for text-filter matching (needs the rendered
        // line to check against). Final formatting happens after prefix/widths.
        val draft = Formatter(
            FormatConfig(
                prettifyValues = settings.prettifyValues,
                timestampDecimals = settings.timestampDecimals,
            )
        )

        data class Kept(val rawIdx: Int, val entry: LogEntry)
        val kept = mutableListOf<Kept>()
        entries.forEachIndexed { rawIdx, entry ->
            val formatted = if (userFiltering) draft.format(entry).text else ""
            if (userFiltering && !filters.accepts(entry, formatted)) return@forEachIndexed
            kept.add(Kept(rawIdx, entry))
        }

        val matchingTargets = kept.mapNotNull { it.entry.target }
        val commonPrefix = if (settings.stripCommonPrefix)
            TargetPrefixDetector.commonPrefix(matchingTargets)
        else ""

        val maxLevelLen = kept.maxOfOrNull { it.entry.level?.length ?: 0 } ?: 0
        val maxTargetDisplayLen = kept.maxOfOrNull {
            it.entry.target?.let { t -> TargetPrefixDetector.strip(t, commonPrefix).length } ?: 0
        } ?: 0
        val maxMessageLen = kept.maxOfOrNull { it.entry.message?.length ?: 0 } ?: 0

        val alignment = settings.alignment
        val finalConfig = FormatConfig(
            targetPrefix = commonPrefix,
            levelPadWidth = if (alignment >= Alignment.TARGETS && maxLevelLen > 0) maxLevelLen + 1 else 0,
            targetPadWidth = if (alignment >= Alignment.MESSAGES && maxTargetDisplayLen > 0) maxTargetDisplayLen + 2 else 0,
            messagePadWidth = if (alignment >= Alignment.FIELDS && maxMessageLen > 0) maxMessageLen + 1 else 0,
            prettifyValues = settings.prettifyValues,
            timestampDecimals = settings.timestampDecimals,
        )
        val formatter = Formatter(finalConfig)

        val mapping = mutableListOf<Int>()
        val structuredLines = mutableListOf<FormattedLine>()
        val rawLines = mutableListOf<String>()
        var count = 0
        var earliest: Instant? = null
        var latest: Instant? = null

        kept.forEach { (rawIdx, entry) ->
            mapping.add(rawIdx)
            structuredLines.add(formatter.format(entry))
            rawLines.add(entry.raw)
            if (entry.isBlank) return@forEach
            count++
            entry.timestamp?.let { ts ->
                if (earliest == null || ts.isBefore(earliest)) earliest = ts
                if (latest == null || ts.isAfter(latest)) latest = ts
            }
        }

        return RebuildResult(
            structuredLines = structuredLines,
            rawLines = rawLines,
            formattedToRawLine = mapping,
            stats = JsonlStats(count, earliest, latest),
            allTargets = allTargets,
        )
    }
}
