package com.olegs.jsonl

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class JsonlFormatterTest {

    private val utc: ZoneId = ZoneOffset.UTC

    @Test
    fun `formats the canonical example`() {
        val line = """{"timestamp":"2026-04-22T21:43:38.033084Z","level":"DEBUG","fields":{"message":"event -> clear","client":"claude","event":"SessionEnd","chat_id":"zed-jsonl-extension"},"target":"ai_agent_dashboard_lib::http_server"}"""
        val expected = "2026-04-22 21:43:38.033084 DEBUG ai_agent_dashboard_lib::http_server: event -> clear client=claude event=SessionEnd chat_id=zed-jsonl-extension"
        assertEquals(expected, JsonlFormatter.formatLine(line, utc))
    }

    @Test
    fun `converts timestamp to the requested zone`() {
        val line = """{"timestamp":"2026-04-22T21:43:38.033084Z","level":"INFO","target":"svc","fields":{"message":"hi"}}"""
        val pdt = ZoneOffset.ofHours(-7)
        assertEquals(
            "2026-04-22 14:43:38.033084 INFO svc: hi",
            JsonlFormatter.formatLine(line, pdt),
        )
    }

    @Test
    fun `handles missing message`() {
        val line = """{"timestamp":"2026-04-22T21:43:38.000000Z","level":"WARN","target":"svc","fields":{"count":3}}"""
        assertEquals(
            "2026-04-22 21:43:38.000000 WARN svc: count=3",
            JsonlFormatter.formatLine(line, utc),
        )
    }

    @Test
    fun `handles missing target`() {
        val line = """{"timestamp":"2026-04-22T21:43:38.000000Z","level":"ERROR","fields":{"message":"boom"}}"""
        assertEquals(
            "2026-04-22 21:43:38.000000 ERROR boom",
            JsonlFormatter.formatLine(line, utc),
        )
    }

    @Test
    fun `handles missing fields entirely`() {
        val line = """{"timestamp":"2026-04-22T21:43:38.000000Z","level":"INFO","target":"svc"}"""
        assertEquals(
            "2026-04-22 21:43:38.000000 INFO svc:",
            JsonlFormatter.formatLine(line, utc),
        )
    }

    @Test
    fun `quotes values containing whitespace`() {
        val line = """{"fields":{"message":"done","path":"/tmp/with space"}}"""
        assertEquals(
            """done path="/tmp/with space"""",
            JsonlFormatter.formatLine(line, utc),
        )
    }

    @Test
    fun `preserves nested object values as JSON`() {
        val line = """{"fields":{"message":"ctx","meta":{"a":1,"b":"x"}}}"""
        assertEquals(
            """ctx meta={"a":1,"b":"x"}""",
            JsonlFormatter.formatLine(line, utc),
        )
    }

    @Test
    fun `non-JSON line passes through`() {
        val line = "===== not JSON ====="
        assertEquals(line, JsonlFormatter.formatLine(line, utc))
    }

    @Test
    fun `malformed JSON passes through`() {
        val line = """{"timestamp":"broken"""
        assertEquals(line, JsonlFormatter.formatLine(line, utc))
    }

    @Test
    fun `invalid timestamp omitted but rest preserved`() {
        val line = """{"timestamp":"not a date","level":"INFO","target":"svc","fields":{"message":"hi"}}"""
        assertEquals(
            "INFO svc: hi",
            JsonlFormatter.formatLine(line, utc),
        )
    }

    @Test
    fun `empty line stays empty`() {
        assertEquals("", JsonlFormatter.formatLine("", utc))
    }

    @Test
    fun `whole file is formatted line by line preserving blanks`() {
        val src = buildString {
            appendLine("""{"timestamp":"2026-04-22T00:00:00.000000Z","level":"INFO","target":"a","fields":{"message":"one"}}""")
            appendLine("")
            appendLine("""plain marker""")
            append("""{"timestamp":"2026-04-22T00:00:01.000000Z","level":"INFO","target":"a","fields":{"message":"two"}}""")
        }
        val expected = buildString {
            appendLine("2026-04-22 00:00:00.000000 INFO a: one")
            appendLine("")
            appendLine("plain marker")
            append("2026-04-22 00:00:01.000000 INFO a: two")
        }
        assertEquals(expected, JsonlFormatter.formatFile(src, utc))
    }

    @Test
    fun `computeStats counts non-blank lines and tracks earliest and latest timestamps`() {
        val src = buildString {
            appendLine("""{"timestamp":"2026-04-22T10:00:00Z","level":"INFO","fields":{"message":"a"}}""")
            appendLine("")
            appendLine("""{"timestamp":"2026-04-22T10:05:00Z","level":"INFO","fields":{"message":"b"}}""")
            appendLine("""plain marker""")
            append("""{"timestamp":"2026-04-22T09:30:00Z","level":"INFO","fields":{"message":"c"}}""")
        }
        val stats = JsonlFormatter.computeStats(src)
        assertEquals(4, stats.count)
        assertEquals(Instant.parse("2026-04-22T09:30:00Z"), stats.firstEntry)
        assertEquals(Instant.parse("2026-04-22T10:05:00Z"), stats.mostRecent)
    }

    @Test
    fun `commonTargetPrefix finds shared segment prefix without trailing separator`() {
        assertEquals(
            "ai_agent_dashboard_lib",
            JsonlFormatter.commonTargetPrefix(
                listOf(
                    "ai_agent_dashboard_lib::http_server",
                    "ai_agent_dashboard_lib::usage_limits",
                    "ai_agent_dashboard_lib::log_watcher",
                )
            ),
        )
    }

    @Test
    fun `commonTargetPrefix truncates LCP back to the last separator (no trailing sep)`() {
        // Character LCP is "foo::bar_" — must truncate to "foo" so we don't cut inside a segment.
        assertEquals("foo", JsonlFormatter.commonTargetPrefix(listOf("foo::bar_x", "foo::bar_y")))
    }

    @Test
    fun `commonTargetPrefix empty when no shared segment`() {
        assertEquals("", JsonlFormatter.commonTargetPrefix(listOf("foo::bar", "baz::qux")))
        assertEquals("", JsonlFormatter.commonTargetPrefix(listOf("just_one")))
        assertEquals("", JsonlFormatter.commonTargetPrefix(emptyList()))
    }

    @Test
    fun `commonTargetPrefix handles a root target mixed with sub-targets`() {
        val targets = listOf(
            "ai_agent_dashboard_lib",
            "ai_agent_dashboard_lib::http_server",
            "ai_agent_dashboard_lib::log_watcher",
        )
        assertEquals("ai_agent_dashboard_lib", JsonlFormatter.commonTargetPrefix(targets))
    }

    @Test
    fun `commonTargetPrefix handles a root dotted target`() {
        val targets = listOf("com.acme", "com.acme.Foo", "com.acme.Bar")
        assertEquals("com.acme", JsonlFormatter.commonTargetPrefix(targets))
    }

    @Test
    fun `commonTargetPrefix returns empty when root has no matching separator`() {
        // "foo" vs "foobar" — no separator could bridge them
        assertEquals("", JsonlFormatter.commonTargetPrefix(listOf("foo", "foobar")))
    }

    @Test
    fun `commonTargetPrefix supports dotted paths`() {
        assertEquals(
            "com.acme.service",
            JsonlFormatter.commonTargetPrefix(listOf("com.acme.service.Foo", "com.acme.service.Bar")),
        )
    }

    @Test
    fun `formatLine with levelPadWidth pads shorter levels to align the next token`() {
        val infoLine = """{"timestamp":"2026-04-22T00:00:00.000000Z","level":"INFO","target":"svc","fields":{"message":"m"}}"""
        val errorLine = """{"timestamp":"2026-04-22T00:00:00.000000Z","level":"ERROR","target":"svc","fields":{"message":"m"}}"""
        // When both INFO and ERROR are in the visible subset, padWidth = 5 + 1 = 6.
        assertEquals(
            "2026-04-22 00:00:00.000000 INFO  svc: m",
            JsonlFormatter.formatLine(infoLine, utc, levelPadWidth = 6),
        )
        assertEquals(
            "2026-04-22 00:00:00.000000 ERROR svc: m",
            JsonlFormatter.formatLine(errorLine, utc, levelPadWidth = 6),
        )
    }

    @Test
    fun `formatLine with messagePadWidth pads shorter messages to align field names`() {
        val short = """{"timestamp":"2026-04-22T00:00:00.000000Z","level":"INFO","fields":{"message":"hi","a":"x"}}"""
        val long = """{"timestamp":"2026-04-22T00:00:00.000000Z","level":"INFO","fields":{"message":"hello there","a":"x"}}"""
        // Widest message "hello there" = 11 chars. messagePadWidth = 11 + 1 = 12.
        assertEquals(
            "2026-04-22 00:00:00.000000 INFO hi          a=x",
            JsonlFormatter.formatLine(short, utc, messagePadWidth = 12),
        )
        assertEquals(
            "2026-04-22 00:00:00.000000 INFO hello there a=x",
            JsonlFormatter.formatLine(long, utc, messagePadWidth = 12),
        )
    }

    @Test
    fun `formatLine with targetPadWidth fills empty target with padding to keep messages aligned`() {
        // Root target collapsed to empty (prefix == target) but align-messages is on.
        val root = """{"timestamp":"2026-04-22T00:00:00.000000Z","level":"INFO","target":"foo","fields":{"message":"m"}}"""
        val sub = """{"timestamp":"2026-04-22T00:00:00.000000Z","level":"INFO","target":"foo::usage_limits","fields":{"message":"m"}}"""
        // Widest target "usage_limits:" = 13. targetPadWidth = 13 + 1 = 14.
        assertEquals(
            "2026-04-22 00:00:00.000000 INFO " + " ".repeat(14) + "m",
            JsonlFormatter.formatLine(root, utc, "foo", targetPadWidth = 14),
        )
        assertEquals(
            "2026-04-22 00:00:00.000000 INFO usage_limits: m",
            JsonlFormatter.formatLine(sub, utc, "foo", targetPadWidth = 14),
        )
    }

    @Test
    fun `formatLine with targetPadWidth pads shorter targets to align messages`() {
        val short = """{"timestamp":"2026-04-22T00:00:00.000000Z","level":"INFO","target":"a","fields":{"message":"m"}}"""
        val long = """{"timestamp":"2026-04-22T00:00:00.000000Z","level":"INFO","target":"bcdef","fields":{"message":"m"}}"""
        // Widest target "bcdef:" = 6. targetPadWidth = 6 + 1 = 7.
        assertEquals(
            "2026-04-22 00:00:00.000000 INFO a:     m",
            JsonlFormatter.formatLine(short, utc, targetPadWidth = 7),
        )
        assertEquals(
            "2026-04-22 00:00:00.000000 INFO bcdef: m",
            JsonlFormatter.formatLine(long, utc, targetPadWidth = 7),
        )
    }

    @Test
    fun `formatLine padWidth 0 disables padding`() {
        val line = """{"timestamp":"2026-04-22T00:00:00.000000Z","level":"INFO","target":"svc","fields":{"message":"m"}}"""
        assertEquals(
            "2026-04-22 00:00:00.000000 INFO svc: m",
            JsonlFormatter.formatLine(line, utc, levelPadWidth = 0),
        )
    }

    @Test
    fun `prettifyValue strips Some and debug-quotes`() {
        assertEquals("13.0", JsonlFormatter.prettifyValue("Some(13.0)"))
        assertEquals("13.0", JsonlFormatter.prettifyValue("Some(Some(13.0))"))
        assertEquals("hello", JsonlFormatter.prettifyValue("Some(\"hello\")"))
        assertEquals("he\"y", JsonlFormatter.prettifyValue("\"he\\\"y\""))  // "he\"y" → he"y
        assertEquals("a\\b", JsonlFormatter.prettifyValue("\"a\\\\b\""))    // "a\\b" → a\b
        assertEquals("None", JsonlFormatter.prettifyValue("None"))
        assertEquals("plain", JsonlFormatter.prettifyValue("plain"))
    }

    @Test
    fun `formatLine with prettifyValues unwraps Some around numbers`() {
        val line = """{"timestamp":"2026-04-22T00:00:00.000000Z","level":"INFO","target":"svc","fields":{"message":"m","five_hour_raw":"Some(13.0)"}}"""
        assertEquals(
            "2026-04-22 00:00:00.000000 INFO svc: m five_hour_raw=13.0",
            JsonlFormatter.formatLine(line, utc, prettifyValues = true),
        )
    }

    @Test
    fun `formatLine with timestampDecimals renders fewer digits`() {
        val line = """{"timestamp":"2026-04-22T21:43:38.033084Z","level":"INFO","fields":{"message":"hi"}}"""
        assertEquals(
            "2026-04-22 21:43:38 INFO hi",
            JsonlFormatter.formatLine(line, utc, timestampDecimals = 0),
        )
        assertEquals(
            "2026-04-22 21:43:38.033 INFO hi",
            JsonlFormatter.formatLine(line, utc, timestampDecimals = 3),
        )
    }

    @Test
    fun `formatLine prettify outputs backslash paths raw (no JSON re-encoding)`() {
        val line = """{"timestamp":"2026-04-22T00:00:00.000000Z","level":"INFO","target":"svc","fields":{"message":"m","path":"C:\\Users\\Oleg\\file.jsonl"}}"""
        assertEquals(
            """2026-04-22 00:00:00.000000 INFO svc: m path=C:\Users\Oleg\file.jsonl""",
            JsonlFormatter.formatLine(line, utc, prettifyValues = true),
        )
    }

    @Test
    fun `formatLine without prettifyValues leaves values untouched`() {
        val line = """{"timestamp":"2026-04-22T00:00:00.000000Z","level":"INFO","target":"svc","fields":{"message":"m","x":"Some(13.0)"}}"""
        assertEquals(
            "2026-04-22 00:00:00.000000 INFO svc: m x=Some(13.0)",
            JsonlFormatter.formatLine(line, utc, prettifyValues = false),
        )
    }

    @Test
    fun `formatLineStructured exposes level and key ranges`() {
        val line = """{"timestamp":"2026-04-22T21:43:38.000000Z","level":"DEBUG","fields":{"message":"hi","a":"x","b":"y"},"target":"svc"}"""
        val structured = JsonlFormatter.formatLineStructured(line, utc)
        assertEquals("2026-04-22 21:43:38.000000 DEBUG svc: hi a=x b=y", structured.text)
        assertEquals("DEBUG", structured.level)
        // Keys "a" and "b" — each 1 char wide
        assertEquals(2, structured.keyRanges.size)
        structured.keyRanges.forEach { r ->
            val char = structured.text.substring(r.first, r.last + 1)
            assertEquals(true, char == "a" || char == "b")
        }
    }

    @Test
    fun `formatLine with targetPrefix strips matching prefix`() {
        val line = """{"timestamp":"2026-04-22T21:43:38.000000Z","level":"DEBUG","fields":{"message":"hi"},"target":"ai_agent_dashboard_lib::http_server"}"""
        assertEquals(
            "2026-04-22 21:43:38.000000 DEBUG http_server: hi",
            JsonlFormatter.formatLine(line, utc, "ai_agent_dashboard_lib::"),
        )
    }

    @Test
    fun `formatLine omits target block when prefix covers the whole target`() {
        val line = """{"timestamp":"2026-04-22T21:43:38.000000Z","level":"DEBUG","fields":{"message":"hi"},"target":"foo"}"""
        // Root target equals the prefix — target block (including the colon) is dropped.
        assertEquals(
            "2026-04-22 21:43:38.000000 DEBUG hi",
            JsonlFormatter.formatLine(line, utc, "foo"),
        )
    }

    @Test
    fun `formatLine strips prefix plus leading separator`() {
        // Prefix passed without trailing '::' — stripping removes leading '::' too.
        val line = """{"timestamp":"2026-04-22T21:43:38.000000Z","level":"DEBUG","fields":{"message":"hi"},"target":"ai_agent_dashboard_lib::usage_limits"}"""
        assertEquals(
            "2026-04-22 21:43:38.000000 DEBUG usage_limits: hi",
            JsonlFormatter.formatLine(line, utc, "ai_agent_dashboard_lib"),
        )
    }

    @Test
    fun `extractLevel returns level field or null`() {
        val line = """{"timestamp":"2026-04-22T10:00:00Z","level":"WARN","fields":{"message":"x"}}"""
        assertEquals("WARN", JsonlFormatter.extractLevel(line))
        assertEquals(null, JsonlFormatter.extractLevel("not json"))
        assertEquals(null, JsonlFormatter.extractLevel("""{"timestamp":"2026-04-22T10:00:00Z"}"""))
    }

    @Test
    fun `extractTimestamp parses ISO timestamp`() {
        val line = """{"timestamp":"2026-04-22T10:00:00Z","level":"INFO"}"""
        assertEquals(Instant.parse("2026-04-22T10:00:00Z"), JsonlFormatter.extractTimestamp(line))
        assertEquals(null, JsonlFormatter.extractTimestamp("""{"level":"INFO"}"""))
        assertEquals(null, JsonlFormatter.extractTimestamp("""{"timestamp":"garbage"}"""))
    }

    @Test
    fun `absoluteShort renders timestamps in EEE, MMM d HH-mm local time`() {
        val instant = Instant.parse("2026-04-22T17:00:00Z")
        assertEquals("Wed, Apr 22 17:00", JsonlFormatter.absoluteShort(instant, utc))
    }

    @Test
    fun `humanRelative reports clock-appropriate units`() {
        val base = Instant.parse("2026-04-22T12:00:00Z")
        assertEquals("just now", JsonlFormatter.humanRelative(base, base))
        assertEquals("just now", JsonlFormatter.humanRelative(base, base.plusSeconds(14)))
        assertEquals("less than a minute ago", JsonlFormatter.humanRelative(base, base.plusSeconds(15)))
        assertEquals("less than a minute ago", JsonlFormatter.humanRelative(base, base.plusSeconds(59)))
        assertEquals("1 minute ago", JsonlFormatter.humanRelative(base, base.plusSeconds(60)))
        assertEquals("1 minute ago", JsonlFormatter.humanRelative(base, base.plusSeconds(90)))
        assertEquals("3 minutes ago", JsonlFormatter.humanRelative(base, base.plusSeconds(200)))
        assertEquals("2 hours ago", JsonlFormatter.humanRelative(base, base.plusSeconds(7200)))
        assertEquals("2 hours 5 minutes ago", JsonlFormatter.humanRelative(base, base.plusSeconds(7500)))
        assertEquals("1 hour 1 minute ago", JsonlFormatter.humanRelative(base, base.plusSeconds(3660)))
        assertEquals("1 day ago", JsonlFormatter.humanRelative(base, base.plusSeconds(86400)))
        assertEquals("1 day 1 hour ago", JsonlFormatter.humanRelative(base, base.plusSeconds(90000)))
        assertEquals("3 days 5 hours ago", JsonlFormatter.humanRelative(base, base.plusSeconds(3 * 86400 + 5 * 3600)))
    }

    @Test
    fun `prettyJson pretty-prints a single JSON line`() {
        val line = """{"a":1,"b":{"c":2}}"""
        val expected = """
            {
              "a": 1,
              "b": {
                "c": 2
              }
            }
        """.trimIndent()
        assertEquals(expected, JsonlFormatter.prettyJson(line))
    }

    @Test
    fun `prettyJson returns empty for blank input`() {
        assertEquals("", JsonlFormatter.prettyJson(""))
        assertEquals("", JsonlFormatter.prettyJson("   "))
    }

    @Test
    fun `prettyJson falls back gracefully for non-JSON`() {
        val line = "not json"
        assertEquals("// not valid JSON\nnot json", JsonlFormatter.prettyJson(line))
    }

    @Test
    fun `unknown top-level keys are surfaced as k=v`() {
        val line = """{"timestamp":"2026-04-22T00:00:00.000000Z","level":"INFO","target":"a","fields":{"message":"m"},"span":"root"}"""
        assertEquals(
            "2026-04-22 00:00:00.000000 INFO a: m span=root",
            JsonlFormatter.formatLine(line, utc),
        )
    }
}
