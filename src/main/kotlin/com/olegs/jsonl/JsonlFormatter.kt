package com.olegs.jsonl

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.google.gson.JsonSyntaxException
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

data class JsonlStats(val count: Int, val firstEntry: Instant?, val mostRecent: Instant?)

data class FormattedLine(
    val text: String,
    val level: String?,
    val keyRanges: List<IntRange>,
    val timestampRange: IntRange? = null,
    val levelRange: IntRange? = null,
    val targetRange: IntRange? = null,
    val messageRange: IntRange? = null,
    val equalsRanges: List<IntRange> = emptyList(),
)

/**
 * Thin compatibility façade. The real work lives in
 *   [LogEntryParser] + [Formatter] + [ValueFormatter] + [TargetPrefixDetector].
 * Kept so that existing tests and external callers keep compiling.
 */
object JsonlFormatter {

    private val absoluteFormatter = DateTimeFormatter.ofPattern("EEE, MMM d HH:mm", Locale.ENGLISH)
    private val prettyGson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

    // --- Formatting façade -----------------------------------------------------

    fun formatFile(text: String, zone: ZoneId = ZoneId.systemDefault(), targetPrefix: String = ""): String =
        text.splitToSequence('\n')
            .map { formatLine(it, zone, targetPrefix) }
            .joinToString("\n")

    fun formatLine(
        line: String,
        zone: ZoneId = ZoneId.systemDefault(),
        targetPrefix: String = "",
        levelPadWidth: Int = 0,
        targetPadWidth: Int = 0,
        messagePadWidth: Int = 0,
        prettifyValues: Boolean = false,
        timestampDecimals: Int = 6,
    ): String = formatLineStructured(
        line, zone, targetPrefix, levelPadWidth, targetPadWidth, messagePadWidth, prettifyValues, timestampDecimals
    ).text

    fun formatLineStructured(
        line: String,
        zone: ZoneId = ZoneId.systemDefault(),
        targetPrefix: String = "",
        levelPadWidth: Int = 0,
        targetPadWidth: Int = 0,
        messagePadWidth: Int = 0,
        prettifyValues: Boolean = false,
        timestampDecimals: Int = 6,
    ): FormattedLine {
        val config = FormatConfig(zone, targetPrefix, levelPadWidth, targetPadWidth, messagePadWidth, prettifyValues, timestampDecimals)
        return Formatter(config).format(LogEntryParser.parse(line))
    }

    // --- Domain helpers (delegates) --------------------------------------------

    fun extractLevel(line: String): String? = LogEntryParser.parse(line).level
    fun extractTarget(line: String): String? = LogEntryParser.parse(line).target
    fun extractMessage(line: String): String? = LogEntryParser.parse(line).message
    fun extractTimestamp(line: String): Instant? = LogEntryParser.parse(line).timestamp

    fun commonTargetPrefix(targets: List<String>): String = TargetPrefixDetector.commonPrefix(targets)
    fun stripTargetPrefix(target: String, prefix: String): String = TargetPrefixDetector.strip(target, prefix)

    fun prettifyValue(raw: String): String = ValueFormatter.prettify(raw)

    // --- Stats + time helpers --------------------------------------------------

    fun computeStats(text: String): JsonlStats {
        var count = 0
        var earliest: Instant? = null
        var latest: Instant? = null
        text.splitToSequence('\n').forEach { rawLine ->
            val entry = LogEntryParser.parse(rawLine)
            if (entry.isBlank) return@forEach
            count++
            val ts = entry.timestamp ?: return@forEach
            if (earliest == null || ts.isBefore(earliest)) earliest = ts
            if (latest == null || ts.isAfter(latest)) latest = ts
        }
        return JsonlStats(count, earliest, latest)
    }

    fun absoluteShort(instant: Instant, zone: ZoneId = ZoneId.systemDefault()): String =
        ZonedDateTime.ofInstant(instant, zone).format(absoluteFormatter)

    fun humanRelative(instant: Instant, now: Instant = Instant.now()): String {
        val seconds = Duration.between(instant, now).seconds.coerceAtLeast(0)
        return when {
            seconds < 15 -> "just now"
            seconds < 60 -> "less than a minute ago"
            seconds < 3600 -> {
                val m = seconds / 60
                "$m ${plural(m, "minute")} ago"
            }
            seconds < 86400 -> {
                val h = seconds / 3600
                val m = (seconds % 3600) / 60
                if (m == 0L) "$h ${plural(h, "hour")} ago"
                else "$h ${plural(h, "hour")} $m ${plural(m, "minute")} ago"
            }
            else -> {
                val d = seconds / 86400
                val h = (seconds % 86400) / 3600
                if (h == 0L) "$d ${plural(d, "day")} ago"
                else "$d ${plural(d, "day")} $h ${plural(h, "hour")} ago"
            }
        }
    }

    private fun plural(n: Long, unit: String) = if (n == 1L) unit else "${unit}s"

    fun prettyJson(line: String): String {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return ""
        return try {
            prettyGson.toJson(JsonParser.parseString(trimmed))
        } catch (_: JsonSyntaxException) {
            "// not valid JSON\n$trimmed"
        }
    }
}
