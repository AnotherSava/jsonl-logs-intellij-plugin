package com.olegs.jsonl

/**
 * Detects the longest segment-aligned common prefix across a set of targets and
 * strips it from individual targets (including a leading separator). Pure data;
 * no IntelliJ dependencies.
 */
object TargetPrefixDetector {

    private val SEGMENT_SEPARATORS = listOf("::", ".", "/")

    /**
     * Common prefix without a trailing separator. Callers use [strip] to remove
     * the prefix plus leading separator from individual targets.
     */
    fun commonPrefix(targets: List<String>): String {
        if (targets.size < 2) return ""
        var lcp = targets.first()
        for (i in 1 until targets.size) {
            lcp = lcpOf(lcp, targets[i])
            if (lcp.isEmpty()) return ""
        }
        val longer = targets.filter { it.length > lcp.length }
        if (longer.isNotEmpty()) {
            for (sep in SEGMENT_SEPARATORS) {
                if (longer.all { it.startsWith(lcp + sep) }) return lcp
            }
        }
        val cut = SEGMENT_SEPARATORS
            .mapNotNull {
                val idx = lcp.lastIndexOf(it)
                if (idx < 0) null else idx
            }
            .maxOrNull() ?: return ""
        return lcp.substring(0, cut)
    }

    fun strip(target: String, prefix: String): String {
        if (prefix.isEmpty() || !target.startsWith(prefix)) return target
        var rest = target.removePrefix(prefix)
        for (sep in SEGMENT_SEPARATORS) {
            if (rest.startsWith(sep)) {
                rest = rest.removePrefix(sep)
                break
            }
        }
        return rest
    }

    private fun lcpOf(a: String, b: String): String {
        val n = minOf(a.length, b.length)
        var i = 0
        while (i < n && a[i] == b[i]) i++
        return a.substring(0, i)
    }
}
