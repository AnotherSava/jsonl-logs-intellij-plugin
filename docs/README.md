# JSONL Log Viewer — Documentation

An IntelliJ Platform plugin that renders `.jsonl` (JSON-per-line) structured log
files as human-readable log lines in a side-by-side split view, with filters,
highlighting, and a JSON inspector for the current entry.

## Example

Given a line like:

```json
{"timestamp":"2026-04-22T21:43:38.033084Z","level":"DEBUG","fields":{"message":"event -> clear","client":"claude","event":"SessionEnd","chat_id":"zed-ext"},"target":"ai_agent_dashboard_lib::http_server"}
```

the plugin displays it as:

```
2026-04-22 21:43:38 DEBUG http_server: event -> clear client=claude event=SessionEnd chat_id=zed-ext
```

with the severity label coloured, target italicized, message bolded, field keys
tinted, and timestamp/`=` dimmed. Customizable through the IDE's Color Scheme.

## Reading order

- [User guide](user-guide.md) — how to install the plugin, open `.jsonl` files,
  configure panes / filters / highlights / field mappings, and use the context
  menu. Start here.
- [Architecture](architecture.md) — domain model, format pipeline, filter chain,
  highlighter strategy, state persistence. Start here if you want to understand
  how the plugin works internally or contribute a change.
- [Development](development.md) — prerequisites, building, running tests,
  deploying, and extending the plugin with new filter predicates / highlight
  tokens / field mappings.

## At a glance

| Aspect | Current design |
|---|---|
| Parsing | One JSON parse per line (`LogEntryParser`), result reused for filter + render |
| Rendering | `Formatter(FormatConfig).format(entry) → FormattedLine` |
| Filters | `FilterChain` of `EntryPredicate` (severity, target, text, non-blank) |
| Highlighting | Custom `EditorHighlighter` reading a sidecar token list from `Document.putUserData(...)` — zero `RangeHighlighter` allocations |
| Colors | `TextAttributesKey`s exposed through the IDE's Color Scheme page |
| Settings | Global `PersistentStateComponent` + per-file `FileEditorState` |
| Testing | 59+ unit tests covering parser, formatter, rebuilder, filters, prefix detection, field mapping |

## File map

Each production source file lives under `src/main/kotlin/com/olegs/jsonl/`.
Sorted by role:

| File | Role |
|---|---|
| `LogEntry.kt` | Parsed-line data record |
| `LogEntryParser.kt` | JSON → `LogEntry` (mapping-aware) |
| `FieldMapping.kt` | Dotted-path field lookups |
| `FormatConfig.kt` | Rendering parameters |
| `Formatter.kt` | `LogEntry` + `FormatConfig` → `FormattedLine` |
| `ValueFormatter.kt` | Per-value rendering + prettify logic |
| `TargetPrefixDetector.kt` | Common-prefix computation + strip |
| `FilterChain.kt` | `EntryPredicate` interface + filters |
| `JsonlSession.kt` | Per-editor filter state |
| `JsonlRebuilder.kt` | Stateless `(source, session, settings) → RebuildResult` |
| `JsonlFormatter.kt` | Compatibility façade for existing API callers |
| `JsonlSettings.kt` | Global settings service (`PersistentStateComponent`) |
| `JsonlConfigurable.kt` | Settings page UI |
| `JsonlColors.kt` | `TextAttributesKey` registry |
| `JsonlColorSettingsPage.kt` | Exposes keys to the Color Scheme editor |
| `JsonlTokenHighlighter.kt` | Custom `EditorHighlighter` + span builder |
| `JsonlFileEditorState.kt` | Per-file state (panes, filters, time display) |
| `JsonlSplitEditorProvider.kt` | `FileEditorProvider` + readState/writeState |
| `JsonlEditor.kt` | The `FileEditor` itself (UI shell + toolbar) |
| `FilterByAction.kt` | "Filter by selection" context-menu action |

Tests live under `src/test/kotlin/com/olegs/jsonl/`.
