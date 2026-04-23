package com.olegs.jsonl

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonSyntaxException
import java.time.Instant

object LogEntryParser {

    fun parse(raw: String, mapping: FieldMapping = FieldMapping.DEFAULT): LogEntry {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || !trimmed.startsWith("{")) {
            return blank(raw, trimmed)
        }
        val obj = try {
            val el = JsonParser.parseString(trimmed)
            if (el.isJsonObject) el.asJsonObject else return blank(raw, trimmed)
        } catch (_: JsonSyntaxException) {
            return blank(raw, trimmed)
        }

        val timestamp = obj.lookup(mapping.timestampPath)?.stringOrNull()?.let { parseInstant(it) }
        val level = obj.lookup(mapping.levelPath)?.stringOrNull()
        val target = obj.lookup(mapping.targetPath)?.stringOrNull()
        val message = obj.lookup(mapping.messagePath)?.stringOrNull()

        // `fields`: optional dedicated container for k=v pairs. If present and an
        // object, render every key under it except the one used for `message`.
        val fields = LinkedHashMap<String, JsonElement>()
        val messageLeaf = mapping.messagePath.substringAfterLast('.')
        val messageContainer = mapping.messagePath.substringBeforeLast('.', "")
        if (mapping.fieldsPath.isNotEmpty()) {
            val fieldsObj = obj.lookup(mapping.fieldsPath)?.takeIf { it.isJsonObject }?.asJsonObject
            fieldsObj?.entrySet()?.forEach { (k, v) ->
                // Drop the message leaf only if the message was read from this exact container.
                if (mapping.fieldsPath == messageContainer && k == messageLeaf) return@forEach
                fields[k] = v
            }
        }

        // Extra top-level keys: every top-level key that isn't consumed by the mapping.
        val consumedTop = buildSet {
            add(mapping.timestampPath.topSegment())
            add(mapping.levelPath.topSegment())
            add(mapping.targetPath.topSegment())
            add(mapping.messagePath.topSegment())
            add(mapping.fieldsPath.topSegment())
        } - ""
        val extra = LinkedHashMap<String, JsonElement>()
        obj.entrySet().forEach { (k, v) -> if (k !in consumedTop) extra[k] = v }

        return LogEntry(raw, trimmed, timestamp, level, target, message, fields, extra)
    }

    private fun String.topSegment(): String = substringBefore('.', this)

    private fun blank(raw: String, trimmed: String): LogEntry =
        LogEntry(raw, trimmed, null, null, null, null, LinkedHashMap(), LinkedHashMap())

    private fun parseInstant(raw: String): Instant? = try {
        Instant.parse(raw)
    } catch (_: Exception) {
        null
    }
}
