package com.olegs.jsonl

import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel

class JsonlConfigurable : BoundConfigurable("JSONL Log Viewer") {

    private val settings = JsonlSettings.getInstance()

    override fun createPanel(): DialogPanel {
        val state = settings.config
        return panel {
            group("Formatted view") {
                row { checkBox("Strip common target prefix").bindSelected(state::stripCommonPrefix) }
                row { checkBox("Colour severity label").bindSelected(state::colourSeverity) }
                row { checkBox("Highlight field names").bindSelected(state::highlightFieldNames) }
                row { checkBox("Bold message text").bindSelected(state::boldMessage) }
                row { checkBox("Italic target").bindSelected(state::italicTarget) }
                row { checkBox("Dim timestamp and '='").bindSelected(state::dimTimestampAndEquals) }
                row { checkBox("Prettify values (strip Rust Some(...), un-escape)").bindSelected(state::prettifyValues) }
                row("Align:") {
                    val combo = javax.swing.JComboBox(Alignment.entries.toTypedArray()).apply {
                        selectedItem = state.alignment
                        toolTipText = "None: no padding.  Targets: pad severity.  Messages: +pad target.  Fields: +pad message."
                    }
                    cell(combo)
                        .onApply { (combo.selectedItem as? Alignment)?.let { state.alignment = it } }
                        .onReset { combo.selectedItem = state.alignment }
                        .onIsModified { combo.selectedItem != state.alignment }
                }
                row("Timestamp seconds decimals (0–9):") {
                    intTextField(0..9).bindIntText(state::timestampDecimals)
                }
            }

            group("Behaviour") {
                row { checkBox("Scroll to the latest entry when a .jsonl file is opened").bindSelected(state::scrollToEndOnOpen) }
            }

            group("Field mapping") {
                row {
                    comment(
                        "Dotted paths into each JSON line. Defaults match Rust tracing " +
                            "(<code>timestamp</code> / <code>level</code> / <code>target</code> / " +
                            "<code>fields.message</code>). Override for pino, Serilog, bunyan, OTel, etc. " +
                            "Leave a path blank to treat that semantic slot as absent."
                    )
                }
                row("Timestamp path:") { textField().bindText(state::fieldTimestampPath) }
                row("Level path:") { textField().bindText(state::fieldLevelPath) }
                row("Target path:") { textField().bindText(state::fieldTargetPath) }
                row("Message path:") { textField().bindText(state::fieldMessagePath) }
                row("Fields container path:") { textField().bindText(state::fieldFieldsPath) }
            }

            group("Colours") {
                row {
                    comment("Customize individual highlight colours under " +
                        "<b>Settings → Editor → Color Scheme → JSONL Log Viewer</b>. " +
                        "Theme-based defaults are used automatically.")
                }
            }
        }
    }

    override fun apply() {
        super.apply()
        settings.notifyChanged()
    }
}
