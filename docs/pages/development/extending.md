---
layout: default
title: Extension points
parent: Development
nav_order: 2
---

## Adding a new filter predicate

`FilterChain.kt` is the place.

1. Add a new `EntryPredicate` implementation:
   ```kotlin
   class ChatIdPredicate(private val chatId: String) : EntryPredicate {
       override fun accepts(entry: LogEntry, formatted: String): Boolean {
           if (chatId.isEmpty()) return true
           val el = entry.fields["chat_id"] ?: return false
           return el.asString == chatId
       }
   }
   ```
2. Add session state: extend `JsonlSession` with the new filter value.
3. Include it in `JsonlRebuilder`'s chain construction (conditionally, keyed on whether the value is the sentinel "inactive" value).
4. Update `isFilterActive()` to account for the new filter.
5. Add UI in `JsonlEditor.installToolbar()` — either a `ComboBoxAction` (like `TargetSelectorAction`) or a `SearchTextField`-style control.
6. Persist it in `JsonlFileEditorState` + provider's `readState`/`writeState`.
7. Add a test in `JsonlRebuilderTest`.

## Adding a new highlight colour

1. Declare the key in `JsonlColors.kt`:
   ```kotlin
   val SPAN_ID: TextAttributesKey = TextAttributesKey.createTextAttributesKey(
       "JSONL_SPAN_ID", DefaultLanguageHighlighterColors.METADATA
   )
   ```
2. Register it with the Color Scheme page — add an `AttributesDescriptor` in `JsonlColorSettingsPage.kt` and a tag in `getAdditionalHighlightingTagToDescriptorMap()`.
3. Have the Formatter emit a range for the token (add a new `IntRange?` on `FormattedLine`, populate it in `Formatter`).
4. Teach `HighlightSpanBuilder.collectLineSpans` to emit a span for the new range with the new key.

No markup-model changes needed — the sidecar highlighter picks up the new span type automatically.

## Adding support for a new log format

Usually a pure configuration change: **Settings → Tools → JSONL Log Viewer → Field mapping**. Use dotted paths. See the [Field-mapping reference](../usage/field-mapping) for canonical examples.

If your format has exotic semantics (timestamps in an unusual encoding, severity as a number rather than a string, etc.), you may need to extend `LogEntryParser.parseInstant` or introduce an alias table for severity levels. Both are localized to small pieces of `LogEntryParser.kt`.

## Adding a new per-file state field

1. Add a `var` to `JsonlFileEditorState` with a default value. Primitives and enums serialize natively; other types need a `readState`/`writeState` contribution.
2. Write the value in `JsonlEditor.getState()`, apply it in `JsonlEditor.setState()`.
3. Add attribute serialization in `JsonlSplitEditorProvider.writeState` / `readState`.

## Adding a new settings toggle

1. Add a `var` to `JsonlSettings.State` with a default.
2. Expose in `JsonlConfigurable.createPanel()` (one-line `row { checkBox(...).bindSelected(state::name) }`).
3. Consume wherever relevant — usually in `HighlightSpanBuilder` (if it affects rendering) or `JsonlRebuilder`'s use of `FormatConfig`.
