package com.olegs.jsonl

import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.highlighter.EditorHighlighter
import com.intellij.openapi.editor.highlighter.HighlighterClient
import com.intellij.openapi.editor.highlighter.HighlighterIterator
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.util.Key
import com.intellij.psi.tree.IElementType
import com.intellij.psi.TokenType
import java.awt.Font

/**
 * One contiguous region of the formatted document. Boundaries come from the
 * Formatter's structured output — no lexing heuristics. `key` is null for
 * "default editor foreground" spans (the gaps between semantic tokens).
 */
data class HighlightSpan(
    val start: Int,
    val end: Int,
    val key: TextAttributesKey?,
    val bold: Boolean = false,
    val italic: Boolean = false,
)

/** Sentinel token type — paint decisions are driven by [HighlightSpan.key]. */
private val JSONL_TOKEN: IElementType = TokenType.WHITE_SPACE

/** Sidecar slot attached to the formatted [Document] containing a contiguous
 *  list of highlight spans covering the entire document (no gaps). */
val JSONL_HIGHLIGHT_SPANS: Key<List<HighlightSpan>> = Key.create("com.olegs.jsonl.highlightSpans")

/**
 * Custom EditorHighlighter that reads a pre-computed token list from the
 * document's user data instead of lexing. Correctness is guaranteed because
 * the Formatter that emitted the text also emitted the token boundaries.
 */
class JsonlTokenHighlighter(private val doc: Document) : EditorHighlighter {

    @Volatile private var scheme: EditorColorsScheme =
        EditorColorsManager.getInstance().globalScheme

    override fun createIterator(startOffset: Int): HighlighterIterator {
        val spans = doc.getUserData(JSONL_HIGHLIGHT_SPANS) ?: emptyList()
        return Iterator(spans, doc, scheme, startOffset)
    }

    override fun setEditor(editor: HighlighterClient) { /* no-op */ }
    override fun setColorScheme(scheme: EditorColorsScheme) { this.scheme = scheme }
    override fun beforeDocumentChange(event: DocumentEvent) { /* no-op */ }
    override fun documentChanged(event: DocumentEvent) { /* no-op */ }

    private class Iterator(
        private val spans: List<HighlightSpan>,
        private val doc: Document,
        private val scheme: EditorColorsScheme,
        startOffset: Int,
    ) : HighlighterIterator {
        private var cursor: Int = spans.indexOfFirst { it.end > startOffset }
            .let { if (it < 0) spans.size else it }

        override fun getStart(): Int = spans.getOrNull(cursor)?.start ?: doc.textLength
        override fun getEnd(): Int = spans.getOrNull(cursor)?.end ?: doc.textLength
        override fun getTokenType(): IElementType = JSONL_TOKEN
        override fun atEnd(): Boolean = cursor >= spans.size
        override fun advance() { cursor++ }
        override fun retreat() { cursor-- }
        override fun getDocument(): Document = doc

        override fun getTextAttributes(): TextAttributes {
            val span = spans.getOrNull(cursor) ?: return DEFAULT
            val base = span.key?.let { scheme.getAttributes(it) }
            if (base == null && !span.bold && !span.italic) return DEFAULT
            val attrs = base?.clone() ?: TextAttributes()
            var fontType = attrs.fontType
            if (span.bold) fontType = fontType or Font.BOLD
            if (span.italic) fontType = fontType or Font.ITALIC
            attrs.fontType = fontType
            return attrs
        }

        private companion object {
            val DEFAULT = TextAttributes()
        }
    }
}

/**
 * Turns a per-line list of structured tokens into a contiguous flat list of
 * [HighlightSpan]s covering the whole formatted document. Respects the user
 * toggles in [JsonlSettings] so off-switches produce no-op `null`-key spans.
 */
object HighlightSpanBuilder {

    fun build(
        structuredLines: List<FormattedLine>,
        cfg: JsonlSettings.State,
    ): List<HighlightSpan> {
        val result = mutableListOf<HighlightSpan>()
        var lineStart = 0
        structuredLines.forEachIndexed { idx, fl ->
            collectLineSpans(fl, lineStart, cfg, result)
            lineStart += fl.text.length
            if (idx != structuredLines.lastIndex) {
                lineStart += 1  // '\n' separator
            }
        }
        // Ensure every offset is covered (fill gaps, sort, merge into contiguous list).
        return densify(result, lineStart)
    }

    private fun collectLineSpans(
        fl: FormattedLine,
        lineStart: Int,
        cfg: JsonlSettings.State,
        out: MutableList<HighlightSpan>,
    ) {
        fun add(range: IntRange?, key: TextAttributesKey?, bold: Boolean = false, italic: Boolean = false) {
            if (range == null) return
            out.add(HighlightSpan(lineStart + range.first, lineStart + range.last + 1, key, bold, italic))
        }

        if (cfg.dimTimestampAndEquals) add(fl.timestampRange, JsonlColors.TIMESTAMP)
        if (cfg.colourSeverity) {
            JsonlColors.attrsForLevel(fl.level)?.let { add(fl.levelRange, it) }
        }
        if (cfg.italicTarget) add(fl.targetRange, JsonlColors.TARGET, italic = true)
        if (cfg.boldMessage) add(fl.messageRange, JsonlColors.MESSAGE, bold = true)
        if (cfg.highlightFieldNames) fl.keyRanges.forEach { add(it, JsonlColors.FIELD_NAME) }
        if (cfg.dimTimestampAndEquals) fl.equalsRanges.forEach { add(it, JsonlColors.EQUALS) }
    }

    /** Sort by start, resolve any overlaps (take the latest span), and fill gaps
     *  with null-key spans so the HighlighterIterator sees continuous coverage. */
    private fun densify(raw: List<HighlightSpan>, docLength: Int): List<HighlightSpan> {
        if (raw.isEmpty()) return listOf(HighlightSpan(0, docLength, null))
        val sorted = raw.sortedBy { it.start }
        val result = mutableListOf<HighlightSpan>()
        var cursor = 0
        for (span in sorted) {
            if (span.start < cursor) continue  // drop overlaps
            if (span.start > cursor) result.add(HighlightSpan(cursor, span.start, null))
            result.add(span)
            cursor = span.end
        }
        if (cursor < docLength) result.add(HighlightSpan(cursor, docLength, null))
        return result
    }
}
