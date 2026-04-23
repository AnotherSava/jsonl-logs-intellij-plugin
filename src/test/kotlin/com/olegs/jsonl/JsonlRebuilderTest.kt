package com.olegs.jsonl

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class JsonlRebuilderTest {

    private fun settings(
        stripCommonPrefix: Boolean = true,
        alignment: Alignment = Alignment.NONE,
        prettifyValues: Boolean = false,
        timestampDecimals: Int = 6,
    ) = JsonlSettings.State(
        stripCommonPrefix = stripCommonPrefix,
        alignment = alignment,
        prettifyValues = prettifyValues,
        timestampDecimals = timestampDecimals,
    )

    private val src = buildString {
        appendLine("""{"timestamp":"2026-04-22T10:00:00Z","level":"INFO","fields":{"message":"a"},"target":"svc::http"}""")
        appendLine("""{"timestamp":"2026-04-22T10:01:00Z","level":"DEBUG","fields":{"message":"b"},"target":"svc::db"}""")
        appendLine("""{"timestamp":"2026-04-22T10:02:00Z","level":"ERROR","fields":{"message":"boom","err":"E1"},"target":"svc::http"}""")
        append("""{"timestamp":"2026-04-22T10:03:00Z","level":"WARN","fields":{"message":"slow"},"target":"svc::http"}""")
    }

    @Test
    fun `no filter keeps every non-blank entry`() {
        val r = JsonlRebuilder.rebuild(src, JsonlSession(), settings())
        assertEquals(4, r.structuredLines.size)
        assertEquals(4, r.stats.count)
        assertEquals(4, r.rawLines.size, "rawLines mirrors the full source when no filter is active")
    }

    @Test
    fun `severity filter drops entries below threshold`() {
        val r = JsonlRebuilder.rebuild(src, JsonlSession(minSeverity = Severity.WARN), settings())
        assertEquals(2, r.structuredLines.size)
        assertEquals(2, r.stats.count)
        assertEquals(2, r.rawLines.size)
    }

    @Test
    fun `target filter keeps exact-match only`() {
        val r = JsonlRebuilder.rebuild(src, JsonlSession(targetFilter = "svc::db"), settings())
        assertEquals(1, r.structuredLines.size)
        assertTrue(r.structuredLines.single().text.contains("b"))
    }

    @Test
    fun `text filter matches raw or formatted representations`() {
        val r = JsonlRebuilder.rebuild(src, JsonlSession(textFilter = "boom"), settings())
        assertEquals(1, r.structuredLines.size)
        assertTrue(r.structuredLines.single().text.contains("boom"))
    }

    @Test
    fun `formattedToRawLine maps filtered indices back to original line numbers`() {
        val r = JsonlRebuilder.rebuild(src, JsonlSession(minSeverity = Severity.WARN), settings())
        // Source lines 0/1 (INFO + DEBUG) filtered out; ERROR/WARN are lines 2 and 3.
        assertEquals(listOf(2, 3), r.formattedToRawLine)
    }

    @Test
    fun `common prefix is stripped from all targets when stripCommonPrefix is on`() {
        val r = JsonlRebuilder.rebuild(src, JsonlSession(), settings(stripCommonPrefix = true))
        // All targets are under "svc::" — strip should leave http/db.
        val texts = r.structuredLines.map { it.text }
        assertTrue(texts.any { it.contains(" http:") }, "http target visible without svc:: prefix")
        assertTrue(texts.any { it.contains(" db:") }, "db target visible without svc:: prefix")
        assertTrue(texts.none { it.contains("svc::") }, "no line retains the svc:: prefix")
    }

    @Test
    fun `common prefix is preserved when stripCommonPrefix is off`() {
        val r = JsonlRebuilder.rebuild(src, JsonlSession(), settings(stripCommonPrefix = false))
        assertTrue(r.structuredLines.all { it.text.contains("svc::") })
    }

    @Test
    fun `alignment TARGETS pads level so targets line up`() {
        val r = JsonlRebuilder.rebuild(src, JsonlSession(), settings(alignment = Alignment.TARGETS))
        // Widest level = ERROR (5 chars). INFO/DEBUG/WARN must render with pad 5 chars.
        val infoLine = r.structuredLines.first { it.level == "INFO" }.text
        val errorLine = r.structuredLines.first { it.level == "ERROR" }.text
        // Find col of the target word on each line
        val infoTargetCol = infoLine.indexOf("http:")
        val errorTargetCol = errorLine.indexOf("http:")
        assertEquals(infoTargetCol, errorTargetCol, "targets at same column across lines")
    }

    @Test
    fun `stats reflect filtered timestamps`() {
        val r = JsonlRebuilder.rebuild(src, JsonlSession(minSeverity = Severity.ERROR), settings())
        assertEquals(1, r.stats.count)
        assertEquals(Instant.parse("2026-04-22T10:02:00Z"), r.stats.firstEntry)
        assertEquals(Instant.parse("2026-04-22T10:02:00Z"), r.stats.mostRecent)
    }

    @Test
    fun `allTargets lists every distinct target regardless of filter`() {
        val r = JsonlRebuilder.rebuild(src, JsonlSession(minSeverity = Severity.ERROR), settings())
        assertEquals(listOf("svc::db", "svc::http"), r.allTargets)
    }

    @Test
    fun `blank lines pass through when no user filter is active but are not counted in stats`() {
        val withBlank = "$src\n\n"
        val r = JsonlRebuilder.rebuild(withBlank, JsonlSession(), settings())
        assertEquals(6, r.structuredLines.size, "4 entries + 2 blank lines preserved")
        assertEquals(4, r.stats.count, "blanks aren't entries")
    }

    @Test
    fun `blank lines are dropped when any user filter is active`() {
        val withBlank = "$src\n\n"
        val r = JsonlRebuilder.rebuild(withBlank, JsonlSession(minSeverity = Severity.TRACE), settings())
        assertEquals(4, r.structuredLines.size)
    }
}
