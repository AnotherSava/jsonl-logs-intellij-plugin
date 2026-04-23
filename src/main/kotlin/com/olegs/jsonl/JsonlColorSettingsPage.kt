package com.olegs.jsonl

import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.PlainSyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.options.colors.AttributesDescriptor
import com.intellij.openapi.options.colors.ColorDescriptor
import com.intellij.openapi.options.colors.ColorSettingsPage

/**
 * Publishes the JSONL semantic [TextAttributesKey]s under
 *   Settings → Editor → Color Scheme → JSONL Log Viewer
 * so users can customise every highlight element via the standard IntelliJ flow.
 */
class JsonlColorSettingsPage : ColorSettingsPage {

    override fun getDisplayName(): String = "JSONL Log Viewer"
    override fun getIcon() = null
    override fun getHighlighter(): SyntaxHighlighter = PlainSyntaxHighlighter()

    override fun getAttributeDescriptors(): Array<AttributesDescriptor> = arrayOf(
        AttributesDescriptor("Timestamp", JsonlColors.TIMESTAMP),
        AttributesDescriptor("Level//Error", JsonlColors.LEVEL_ERROR),
        AttributesDescriptor("Level//Warn", JsonlColors.LEVEL_WARN),
        AttributesDescriptor("Level//Info", JsonlColors.LEVEL_INFO),
        AttributesDescriptor("Level//Debug", JsonlColors.LEVEL_DEBUG),
        AttributesDescriptor("Level//Trace", JsonlColors.LEVEL_TRACE),
        AttributesDescriptor("Target", JsonlColors.TARGET),
        AttributesDescriptor("Message", JsonlColors.MESSAGE),
        AttributesDescriptor("Field name", JsonlColors.FIELD_NAME),
        AttributesDescriptor("Equals sign", JsonlColors.EQUALS),
    )

    override fun getColorDescriptors(): Array<ColorDescriptor> = ColorDescriptor.EMPTY_ARRAY

    override fun getDemoText(): String = """
        <ts>2026-04-22 14:31:32</ts> <lvl_info>INFO </lvl_info> <tgt>http_server:</tgt> <msg>listening</msg> <fname>addr</fname><eq>=</eq>127.0.0.1:9077
        <ts>2026-04-22 14:31:33</ts> <lvl_debug>DEBUG</lvl_debug> <tgt>usage_limits:</tgt> <msg>poll</msg> <fname>five_hour</fname><eq>=</eq>13.0
        <ts>2026-04-22 14:31:43</ts> <lvl_warn>WARN </lvl_warn> <tgt>log_watcher:</tgt> <msg>slow flush</msg> <fname>latency_ms</fname><eq>=</eq>240
        <ts>2026-04-22 14:31:45</ts> <lvl_error>ERROR</lvl_error> <tgt>log_watcher:</tgt> <msg>transcript watch failed</msg> <fname>parent</fname><eq>=</eq>/var/log/app
        <ts>2026-04-22 14:31:46</ts> <lvl_trace>TRACE</lvl_trace> <tgt>http_server:</tgt> <msg>trace span</msg> <fname>span_id</fname><eq>=</eq>7f3a
    """.trimIndent()

    override fun getAdditionalHighlightingTagToDescriptorMap(): Map<String, TextAttributesKey> = mapOf(
        "ts" to JsonlColors.TIMESTAMP,
        "lvl_error" to JsonlColors.LEVEL_ERROR,
        "lvl_warn" to JsonlColors.LEVEL_WARN,
        "lvl_info" to JsonlColors.LEVEL_INFO,
        "lvl_debug" to JsonlColors.LEVEL_DEBUG,
        "lvl_trace" to JsonlColors.LEVEL_TRACE,
        "tgt" to JsonlColors.TARGET,
        "msg" to JsonlColors.MESSAGE,
        "fname" to JsonlColors.FIELD_NAME,
        "eq" to JsonlColors.EQUALS,
    )
}
