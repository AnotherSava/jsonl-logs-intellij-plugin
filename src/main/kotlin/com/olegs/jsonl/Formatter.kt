package com.olegs.jsonl

import com.google.gson.JsonElement
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

/**
 * Renders a [LogEntry] using a [FormatConfig]. Stateless aside from the
 * per-decimals DateTimeFormatter cache, which is process-wide.
 */
class Formatter(private val config: FormatConfig) {

    fun format(entry: LogEntry): FormattedLine {
        if (entry.isBlank || !entry.isJson) {
            return FormattedLine(entry.raw, null, emptyList())
        }

        val ts = entry.timestamp?.let {
            ZonedDateTime.ofInstant(it, config.zone).format(timestampFormatter(config.timestampDecimals))
        }
        val displayTarget = entry.target?.let { TargetPrefixDetector.strip(it, config.targetPrefix) }

        val sb = StringBuilder()
        val keyRanges = mutableListOf<IntRange>()
        val equalsRanges = mutableListOf<IntRange>()
        var timestampRange: IntRange? = null
        var levelRange: IntRange? = null
        var targetRange: IntRange? = null
        var messageRange: IntRange? = null

        fun sep() { if (sb.isNotEmpty() && !sb.endsWith(' ')) sb.append(' ') }

        if (ts != null) {
            val start = sb.length
            sb.append(ts)
            timestampRange = start until sb.length
        }
        entry.level?.let { level ->
            sep()
            val start = sb.length
            sb.append(level)
            levelRange = start until sb.length
            val pad = config.levelPadWidth - level.length
            if (pad > 0) repeat(pad) { sb.append(' ') }
        }
        run {
            val show = !displayTarget.isNullOrEmpty()
            if (show || config.targetPadWidth > 0) {
                sep()
                val start = sb.length
                if (show) {
                    sb.append(displayTarget)
                    sb.append(':')
                    targetRange = start until sb.length
                }
                val pad = config.targetPadWidth - (sb.length - start)
                if (pad > 0) repeat(pad) { sb.append(' ') }
            }
        }
        run {
            val show = !entry.message.isNullOrEmpty()
            if (show || config.messagePadWidth > 0) {
                sep()
                val start = sb.length
                if (show) {
                    sb.append(entry.message)
                    messageRange = start until sb.length
                }
                val pad = config.messagePadWidth - (sb.length - start)
                if (pad > 0) repeat(pad) { sb.append(' ') }
            }
        }

        fun appendKv(k: String, v: JsonElement) {
            sep()
            val keyStart = sb.length
            sb.append(k)
            keyRanges.add(keyStart until sb.length)
            val eqStart = sb.length
            sb.append('=')
            equalsRanges.add(eqStart until sb.length)
            sb.append(ValueFormatter.format(v, config.prettifyValues))
        }
        entry.fields.forEach { (k, v) -> appendKv(k, v) }
        entry.extraTopLevel.forEach { (k, v) -> appendKv(k, v) }

        val text = sb.toString().ifEmpty { entry.raw }
        return FormattedLine(text, entry.level, keyRanges, timestampRange, levelRange, targetRange, messageRange, equalsRanges)
    }

    companion object {
        private val formatters = ConcurrentHashMap<Int, DateTimeFormatter>()

        fun timestampFormatter(decimals: Int): DateTimeFormatter =
            formatters.computeIfAbsent(decimals.coerceIn(0, 9)) { n ->
                val pattern = if (n == 0) "yyyy-MM-dd HH:mm:ss" else "yyyy-MM-dd HH:mm:ss." + "S".repeat(n)
                DateTimeFormatter.ofPattern(pattern)
            }
    }
}
