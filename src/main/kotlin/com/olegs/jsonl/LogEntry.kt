package com.olegs.jsonl

import com.google.gson.JsonElement
import java.time.Instant

/**
 * One parsed JSONL line. Produced by [LogEntryParser] and consumed by the
 * formatting + filter pipeline. Parsing is done exactly once per line.
 */
data class LogEntry(
    val raw: String,
    val trimmed: String,
    val timestamp: Instant?,
    val level: String?,
    val target: String?,
    val message: String?,
    /** fields object entries with `message` removed, insertion order preserved. */
    val fields: LinkedHashMap<String, JsonElement>,
    /** top-level JSON keys outside the standard set, insertion order preserved. */
    val extraTopLevel: LinkedHashMap<String, JsonElement>,
) {
    val isBlank: Boolean get() = trimmed.isEmpty()
    val isJson: Boolean get() = trimmed.startsWith("{")
}
