package com.olegs.jsonl

import com.google.gson.JsonElement
import com.google.gson.JsonPrimitive

/**
 * Renders a JSON value as a `key=value` RHS. In prettify mode, string values are
 * emitted raw (no JSON re-encoding) and Rust Debug artefacts (`Some(..)`,
 * surrounding debug quotes, `\"`/`\\` escapes) are collapsed to the underlying
 * literal value.
 */
object ValueFormatter {

    fun format(v: JsonElement, prettify: Boolean): String {
        if (v.isJsonNull) return "null"
        if (v.isJsonPrimitive) {
            val p = v.asJsonPrimitive
            if (p.isString) {
                var s = p.asString
                if (prettify) {
                    s = prettify(s)
                    return s
                }
                return if (needsQuoting(s)) JsonPrimitive(s).toString() else s
            }
            return p.asString
        }
        return v.toString()
    }

    fun prettify(raw: String): String {
        var s = raw
        while (s.length >= 6 && s.startsWith("Some(") && s.endsWith(")")) {
            s = s.substring(5, s.length - 1)
        }
        if (s.length >= 2 && s.startsWith('"') && s.endsWith('"')) {
            s = s.substring(1, s.length - 1)
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
        }
        return s
    }

    private fun needsQuoting(s: String): Boolean {
        if (s.isEmpty()) return true
        return s.any { it.isWhitespace() || it == '"' || it == '\\' || it == '=' }
    }
}
