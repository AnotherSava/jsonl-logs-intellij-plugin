package com.olegs.jsonl

import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey

/**
 * Semantic [TextAttributesKey]s for each JSONL highlight element. Registering
 * these lets IntelliJ's Color Scheme system own the actual colours — users can
 * customise via Settings → Editor → Color Scheme → JSONL Log Viewer, and
 * theme-based overrides / exported schemes work automatically.
 *
 * Default fallbacks are chosen from the language-neutral palette so the plugin
 * looks reasonable even before the scheme provider is consulted.
 */
object JsonlColors {
    val TIMESTAMP: TextAttributesKey = TextAttributesKey.createTextAttributesKey(
        "JSONL_TIMESTAMP", DefaultLanguageHighlighterColors.LINE_COMMENT
    )
    val LEVEL_ERROR: TextAttributesKey = TextAttributesKey.createTextAttributesKey(
        "JSONL_LEVEL_ERROR", DefaultLanguageHighlighterColors.NUMBER
    )
    val LEVEL_WARN: TextAttributesKey = TextAttributesKey.createTextAttributesKey(
        "JSONL_LEVEL_WARN", DefaultLanguageHighlighterColors.METADATA
    )
    val LEVEL_INFO: TextAttributesKey = TextAttributesKey.createTextAttributesKey(
        "JSONL_LEVEL_INFO", DefaultLanguageHighlighterColors.KEYWORD
    )
    val LEVEL_DEBUG: TextAttributesKey = TextAttributesKey.createTextAttributesKey(
        "JSONL_LEVEL_DEBUG", DefaultLanguageHighlighterColors.LINE_COMMENT
    )
    val LEVEL_TRACE: TextAttributesKey = TextAttributesKey.createTextAttributesKey(
        "JSONL_LEVEL_TRACE", DefaultLanguageHighlighterColors.BLOCK_COMMENT
    )
    val TARGET: TextAttributesKey = TextAttributesKey.createTextAttributesKey(
        "JSONL_TARGET", DefaultLanguageHighlighterColors.IDENTIFIER
    )
    val MESSAGE: TextAttributesKey = TextAttributesKey.createTextAttributesKey(
        "JSONL_MESSAGE", DefaultLanguageHighlighterColors.STRING
    )
    val FIELD_NAME: TextAttributesKey = TextAttributesKey.createTextAttributesKey(
        "JSONL_FIELD_NAME", DefaultLanguageHighlighterColors.INSTANCE_FIELD
    )
    val EQUALS: TextAttributesKey = TextAttributesKey.createTextAttributesKey(
        "JSONL_EQUALS", DefaultLanguageHighlighterColors.OPERATION_SIGN
    )

    fun attrsForLevel(level: String?): TextAttributesKey? = when (level?.uppercase()) {
        "ERROR" -> LEVEL_ERROR
        "WARN", "WARNING" -> LEVEL_WARN
        "INFO" -> LEVEL_INFO
        "DEBUG" -> LEVEL_DEBUG
        "TRACE" -> LEVEL_TRACE
        else -> null
    }
}
