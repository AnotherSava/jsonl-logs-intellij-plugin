package com.olegs.jsonl

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class LogEntryParserTest {

    @Test
    fun `default mapping handles Rust tracing-style JSON`() {
        val line = """{"timestamp":"2026-04-22T10:00:00Z","level":"INFO","target":"http","fields":{"message":"hi","client":"claude"}}"""
        val entry = LogEntryParser.parse(line)
        assertEquals(Instant.parse("2026-04-22T10:00:00Z"), entry.timestamp)
        assertEquals("INFO", entry.level)
        assertEquals("http", entry.target)
        assertEquals("hi", entry.message)
        assertEquals(mapOf("client" to "claude"), entry.fields.mapValues { it.value.asString })
    }

    @Test
    fun `pino-style mapping reads time, level as string, msg at top level`() {
        val pinoMapping = FieldMapping(
            timestampPath = "time",
            levelPath = "level",
            targetPath = "name",
            messagePath = "msg",
            fieldsPath = "",
        )
        val line = """{"time":"2026-04-22T10:00:00Z","level":"info","name":"app","msg":"hi","client":"c"}"""
        val entry = LogEntryParser.parse(line, pinoMapping)
        assertEquals(Instant.parse("2026-04-22T10:00:00Z"), entry.timestamp)
        assertEquals("info", entry.level)
        assertEquals("app", entry.target)
        assertEquals("hi", entry.message)
        // With fieldsPath="" there's no dedicated container — "client" appears in extraTopLevel.
        assertEquals(mapOf("client" to "c"), entry.extraTopLevel.mapValues { it.value.asString })
        assertTrue(entry.fields.isEmpty())
    }

    @Test
    fun `serilog-style mapping handles dotted paths`() {
        val serilogMapping = FieldMapping(
            timestampPath = "@t",
            levelPath = "@l",
            targetPath = "SourceContext",
            messagePath = "@mt",
            fieldsPath = "",
        )
        val line = """{"@t":"2026-04-22T10:00:00Z","@l":"Warning","@mt":"user {id} login","SourceContext":"Auth","id":42}"""
        val entry = LogEntryParser.parse(line, serilogMapping)
        assertEquals("Warning", entry.level)
        assertEquals("Auth", entry.target)
        assertEquals("user {id} login", entry.message)
        assertEquals(42, entry.extraTopLevel["id"]?.asInt)
    }

    @Test
    fun `empty path means absent for that semantic slot`() {
        val mapping = FieldMapping(
            timestampPath = "timestamp",
            levelPath = "level",
            targetPath = "",  // skip target
            messagePath = "fields.message",
            fieldsPath = "fields",
        )
        val line = """{"timestamp":"2026-04-22T10:00:00Z","level":"INFO","target":"ignored","fields":{"message":"hi"}}"""
        val entry = LogEntryParser.parse(line, mapping)
        assertNull(entry.target, "target slot disabled by empty path")
        // But `target` is still present in JSON; with empty targetPath it lands in extraTopLevel.
        assertEquals("ignored", entry.extraTopLevel["target"]?.asString)
    }

    @Test
    fun `non-JSON input yields a blank entry`() {
        val entry = LogEntryParser.parse("not json")
        assertNull(entry.level)
        assertNull(entry.target)
        assertNull(entry.message)
        assertTrue(entry.fields.isEmpty())
    }
}
