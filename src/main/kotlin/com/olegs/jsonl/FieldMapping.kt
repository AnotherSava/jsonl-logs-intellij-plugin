package com.olegs.jsonl

import com.google.gson.JsonElement
import com.google.gson.JsonObject

/**
 * Dotted-path locations of the semantic fields inside each JSON line. Defaults
 * match the Rust `tracing_subscriber::fmt::json` layout but can be overridden
 * per project to fit pino / Serilog / bunyan / OpenTelemetry / etc.
 *
 * Path syntax: dot-separated lookup down nested objects. e.g. `fields.message`
 * walks into `fields` then reads `message`. Empty path means "absent" — the
 * parser treats that semantic slot as unavailable.
 */
data class FieldMapping(
    val timestampPath: String = "timestamp",
    val levelPath: String = "level",
    val targetPath: String = "target",
    val messagePath: String = "fields.message",
    /**
     * Object that contains ancillary k=v pairs. Everything under it (minus
     * [messagePath]'s leaf if it resolves inside) is rendered after the message.
     * Empty means "take every top-level key except the standard ones".
     */
    val fieldsPath: String = "fields",
) {
    companion object {
        val DEFAULT = FieldMapping()
    }
}

/** Dotted-path lookup against a [JsonObject]. Returns null on any miss. */
fun JsonObject.lookup(path: String): JsonElement? {
    if (path.isEmpty()) return null
    var current: JsonElement = this
    for (segment in path.split('.')) {
        if (!current.isJsonObject) return null
        current = current.asJsonObject.get(segment) ?: return null
    }
    return current
}

internal fun JsonElement.stringOrNull(): String? {
    if (isJsonNull || !isJsonPrimitive) return null
    return asString
}
