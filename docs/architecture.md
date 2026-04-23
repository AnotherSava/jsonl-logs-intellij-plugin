# Architecture

## Design principles

1. **Parse each line exactly once.** The JSON string of a log entry is parsed
   into a strongly-typed `LogEntry` by `LogEntryParser`. Every downstream
   stage (filter, format, stats, alignment width computation) consumes that
   `LogEntry` — no re-parsing.
2. **Pipeline, not monolith.** Rendering is a composition of small stateless
   pieces: parser → filter chain → formatter → highlight-span builder →
   `EditorHighlighter`. Each stage has a narrow input/output contract and can
   be tested in isolation.
3. **Pure logic is Swing-free.** `JsonlFormatter`, `JsonlRebuilder`, `Formatter`,
   `LogEntryParser`, `TargetPrefixDetector`, `ValueFormatter`, and every
   filter predicate are pure Kotlin, depend only on Gson + `java.time`, and
   are unit-tested without spinning up the IntelliJ platform.
4. **Let IntelliJ drive the paint cycle.** Highlights are published as sidecar
   data on a `Document` and read by a custom `EditorHighlighter`. No
   imperative `RangeHighlighter` management, no per-keystroke markup-model
   churn.
5. **Native integration wins over re-implementation.** Colors live in the
   Color Scheme system (`TextAttributesKey`). Per-file state lives in
   `FileEditorState`. Global settings live in `PersistentStateComponent`.
   All three interop with the IDE's built-in export/import/sync flows.

## Directory layout

```
src/main/kotlin/com/olegs/jsonl/
├── LogEntry.kt                  # parsed-line data record
├── LogEntryParser.kt            # JSON → LogEntry
├── FieldMapping.kt              # dotted-path lookups
├── FormatConfig.kt              # rendering parameters
├── Formatter.kt                 # LogEntry + FormatConfig → FormattedLine
├── ValueFormatter.kt            # value rendering + prettify
├── TargetPrefixDetector.kt      # common prefix detection + strip
├── FilterChain.kt               # EntryPredicate interface + impls
├── JsonlSession.kt              # per-editor filter state snapshot
├── JsonlRebuilder.kt            # stateless orchestrator (the main entry point)
├── JsonlFormatter.kt            # compatibility façade (thin delegate)
├── JsonlSettings.kt             # global settings service
├── JsonlConfigurable.kt         # settings-page UI
├── JsonlColors.kt               # TextAttributesKey registry
├── JsonlColorSettingsPage.kt    # exposes keys to Color Scheme editor
├── JsonlTokenHighlighter.kt     # custom EditorHighlighter + span builder
├── JsonlFileEditorState.kt      # per-file state (panes, filters, time display)
├── JsonlSplitEditorProvider.kt  # FileEditorProvider + readState/writeState
├── JsonlEditor.kt               # FileEditor shell (UI + toolbar + lifecycle)
└── FilterByAction.kt            # "Filter by selection" context action

src/main/resources/META-INF/
└── plugin.xml                   # plugin metadata, extensions, actions

src/test/kotlin/com/olegs/jsonl/
├── JsonlFormatterTest.kt        # formatter façade + value/timestamp/prefix tests
├── JsonlRebuilderTest.kt        # full-rebuild end-to-end tests
└── LogEntryParserTest.kt        # mapping tests (Rust / pino / Serilog / empty paths)
```

## Data flow

```
 .jsonl file
      │
      ▼
 source String (from textEditor.document)
      │
      ▼  source.split('\n').map { LogEntryParser.parse(it, mapping) }
 List<LogEntry>
      │
      ├──▶ allTargetsSet (for Target dropdown, before filter)
      │
      ▼  FilterChain.accepts(entry, draft-formatted text)
 kept: List<Kept(rawIdx, entry)>
      │
      ▼  TargetPrefixDetector.commonPrefix(matchingTargets)
      ▼  maxLevelLen / maxTargetDisplayLen / maxMessageLen
      ▼  FormatConfig(prefix, pad widths, prettify, decimals)
      ▼  Formatter(cfg).format(entry) ——▶ FormattedLine per entry
 RebuildResult(structuredLines, rawLines, mapping, stats, allTargets)
      │
      ▼  HighlightSpanBuilder.build(structuredLines, settings)
 List<HighlightSpan>  (absolute-offset, continuous coverage)
      │
      ▼  (in a single write action)
      │  formattedDoc.setText(structuredLines.join("\n"))
      │  formattedDoc.putUserData(JSONL_HIGHLIGHT_SPANS, spans)
      │  filteredRawDoc.setText(rawLines.join("\n"))
      ▼
 EditorHighlighter on formattedEditor picks up new spans on next paint.
```

## Domain model

### `LogEntry` (`LogEntry.kt`)

```kotlin
data class LogEntry(
    val raw: String,                               // the original line, verbatim
    val trimmed: String,                           // raw.trim()
    val timestamp: Instant?,                       // parsed from the mapped path
    val level: String?,                            // as read, no case folding
    val target: String?,                           // raw value, prefix-strip at render time
    val message: String?,
    val fields: LinkedHashMap<String, JsonElement>,     // fields object, minus message leaf
    val extraTopLevel: LinkedHashMap<String, JsonElement>, // top-level keys not consumed by mapping
)
```

Blank lines and non-JSON lines produce a `LogEntry` with `raw` set and every
other slot null/empty. `isBlank` / `isJson` helpers provide the obvious
predicates.

### `FieldMapping` (`FieldMapping.kt`)

Five dotted paths that tell the parser where each semantic field lives:

```kotlin
data class FieldMapping(
    val timestampPath: String = "timestamp",
    val levelPath: String = "level",
    val targetPath: String = "target",
    val messagePath: String = "fields.message",
    val fieldsPath: String = "fields",
)
```

The defaults match `tracing_subscriber::fmt::json`. Empty path means "this
semantic slot is absent". `JsonObject.lookup(path)` walks the dotted
segments.

### `FormatConfig` (`FormatConfig.kt`)

Immutable value object that replaces an eight-argument `formatLine`:

```kotlin
data class FormatConfig(
    val zone: ZoneId = ZoneId.systemDefault(),
    val targetPrefix: String = "",
    val levelPadWidth: Int = 0,
    val targetPadWidth: Int = 0,
    val messagePadWidth: Int = 0,
    val prettifyValues: Boolean = false,
    val timestampDecimals: Int = 6,
)
```

Zero pad widths disable that padding level.

### `FormattedLine` (`JsonlFormatter.kt`)

What the formatter produces for one line. Carries the rendered text plus
character-offset ranges for each semantic token, which the highlight-span
builder translates into absolute document offsets.

```kotlin
data class FormattedLine(
    val text: String,
    val level: String?,                        // source level, for colour dispatch
    val keyRanges: List<IntRange>,             // each field-name position
    val timestampRange: IntRange? = null,
    val levelRange: IntRange? = null,
    val targetRange: IntRange? = null,
    val messageRange: IntRange? = null,
    val equalsRanges: List<IntRange> = emptyList(),
)
```

### `HighlightSpan` (`JsonlTokenHighlighter.kt`)

One contiguous run of text with its semantic key and font modifiers:

```kotlin
data class HighlightSpan(
    val start: Int,           // absolute offset in the formatted document
    val end: Int,
    val key: TextAttributesKey?,   // null = default foreground
    val bold: Boolean = false,
    val italic: Boolean = false,
)
```

## Components

### Parser (`LogEntryParser.kt`)

Stateless. One public entry point: `parse(raw: String, mapping = FieldMapping.DEFAULT)`.
Handles malformed JSON, missing fields, unusual mappings (including empty
paths and nested message paths like `fields.message` where the fields object
is also being iterated).

### Formatter (`Formatter.kt`)

```kotlin
class Formatter(private val config: FormatConfig) {
    fun format(entry: LogEntry): FormattedLine
}
```

Builds a `StringBuilder` with separator-aware token emission (`sep()` drops a
single space unless one's already there). Computes offsets for every
semantic range while it builds. Applies prettification to string values via
`ValueFormatter` when enabled. Resolves the timestamp pattern through a
`ConcurrentHashMap` cache keyed by decimal precision.

### Value formatter (`ValueFormatter.kt`)

Renders one `JsonElement` as a `key=value` RHS. In prettify mode strings
emit raw (no JSON re-encoding) so backslash-containing paths render as
`path=C:\Users\Oleg\log.jsonl` instead of `path="C:\\Users\\Oleg\\log.jsonl"`.
`prettify(raw)` strips Rust Debug wrappers: recursive `Some(...)`
unwrapping, then debug-quote un-escaping (`\"` → `"`, `\\` → `\`).

### Target prefix detector (`TargetPrefixDetector.kt`)

`commonPrefix(targets)` returns the longest shared segment-aligned prefix
without the trailing separator. `strip(target, prefix)` removes the prefix
**plus** the leading separator from individual targets, so
`ai_agent_dashboard_lib::usage_limits` becomes `usage_limits` and the bare
root `ai_agent_dashboard_lib` becomes `""` (which the formatter then omits
entirely unless alignment is on).

### Filter chain (`FilterChain.kt`)

```kotlin
interface EntryPredicate {
    fun accepts(entry: LogEntry, formatted: String): Boolean
}

class FilterChain(predicates: List<EntryPredicate>) {
    val isActive: Boolean          // true iff the chain has any predicate
    fun accepts(entry: LogEntry, formatted: String): Boolean
}
```

Implementations: `NonBlankPredicate`, `SeverityPredicate(min)`,
`TargetPredicate(exact)`, `TextPredicate(caseInsensitiveSubstring)`. The
text predicate takes the draft-formatted text so it can match either raw
JSON or the rendered line.

### Rebuilder (`JsonlRebuilder.kt`)

The heart of the pipeline. Stateless:

```kotlin
object JsonlRebuilder {
    fun rebuild(source: String, session: JsonlSession, settings: JsonlSettings.State): RebuildResult
}
```

What it does, in order:

1. Parse every line into `LogEntry`.
2. Collect the full set of distinct targets (for the Target dropdown,
   independent of filters).
3. Build a `FilterChain` based on session's (severity, target, text) — if
   none of them is active, the chain is empty and skipped entirely.
4. For active filtering, draft-format each entry (with default FormatConfig)
   just to produce the text for `TextPredicate` to match against.
5. Compute `commonPrefix` from matching targets (if `stripCommonPrefix`),
   and max level / target-display / message lengths from the kept subset.
6. Build the real `FormatConfig` (with prefix + pad widths derived from the
   cascading alignment level).
7. Format each kept entry into a `FormattedLine`, collect `rawLines` and
   stats while at it.
8. Return `RebuildResult(structuredLines, rawLines, formattedToRawLine,
   stats, allTargets)`.

`RebuildResult` is a plain data record — no side effects, no IntelliJ types.
Fully unit-testable (`src/test/kotlin/com/olegs/jsonl/JsonlRebuilderTest.kt`).

### Token highlighter (`JsonlTokenHighlighter.kt`)

Custom `EditorHighlighter` that reads a pre-computed `List<HighlightSpan>`
from `Document.putUserData(JSONL_HIGHLIGHT_SPANS, …)`. The token boundaries
come from the Formatter's `FormattedLine` ranges (which the formatter
knows precisely because it wrote the string) — **no heuristic lexing**.

`HighlightSpanBuilder.build(lines, cfg)` turns per-line ranges into absolute
offsets, fills gaps with null-key spans, and resolves settings toggles (if
`dimTimestampAndEquals` is off, no span is emitted for timestamps).

The `HighlighterIterator` walks the span list, resolving
`TextAttributesKey → TextAttributes` through the current color scheme on
each `getTextAttributes()` call, then layers `Font.BOLD` / `Font.ITALIC` as
modifiers. This keeps colour-scheme switches free — the next paint just
resolves differently.

### Settings service (`JsonlSettings.kt`)

Application-level `PersistentStateComponent`. State layout:

```kotlin
data class State(
    var schemaVersion: Int = 1,                   // for future migrations
    // display toggles (7 booleans)
    // layout: Alignment
    // formatting: timestampDecimals: Int
    // behaviour: scrollToEndOnOpen: Boolean
    // field mapping (5 strings)
)
```

`notifyChanged()` publishes on `JsonlSettingsListener.TOPIC`; every open
`JsonlEditor` subscribes and rebuilds on change.

### Configurable (`JsonlConfigurable.kt`)

Kotlin UI DSL v2 form under **Settings → Tools → JSONL Log Viewer**. Groups:
Formatted view (7 checkboxes + alignment combo + decimals int field),
Behaviour (1 checkbox), Field mapping (5 text fields), Colours (pointer to
Color Scheme page).

### Color scheme integration (`JsonlColors.kt` + `JsonlColorSettingsPage.kt`)

Ten semantic `TextAttributesKey`s, each with a palette-friendly default
(`DefaultLanguageHighlighterColors.*`). `ColorSettingsPage` registers them
under a dedicated **JSONL Log Viewer** page with a demo text block, so
users customize colours through the same flow they use for Java / Kotlin /
JSON syntax colours.

### Per-file state (`JsonlFileEditorState.kt` + `JsonlSplitEditorProvider.kt`)

Six fields — `leftPane`, `rightPane`, `minSeverity`, `targetFilter`,
`textFilter`, `timeDisplay` — persisted as attributes on the project
workspace XML element:

```xml
<provider editor-type-id="jsonl-split-editor">
  <state leftPane="FORMATTED" rightPane="INSPECT" minSeverity="WARN"
         timeDisplay="ABSOLUTE" textFilter="chat_id" />
</provider>
```

`JsonlSplitEditorProvider.writeState` / `readState` do the serialization.
`JsonlEditor.getState` / `setState` apply incoming state on editor open
(including triggering a filter rebuild with the saved filter values).

### The editor (`JsonlEditor.kt`)

The `FileEditor` shell. Owns:

- Three synthetic editors — `formattedEditor` (plain-text viewer),
  `filteredRawEditor` (plain-text viewer, always shown for Raw pane),
  `inspectEditor` (JSON editor, viewer mode).
- The real `textEditor` — kept alive for document event listening + IDE
  file-system integration but never shown in the UI.
- The toolbar `DefaultActionGroup` with gear icon, pane pickers, filters,
  stats labels, and clickable time-display labels.
- One `Alarm` for debouncing filter input (200 ms) and another for
  debouncing document change rebuilds (100 ms).
- A third alarm ticking every 30 s to refresh the "most recent" relative
  time so it walks forward without a document edit.

Caret listeners on all three viewer editors keep Inspect pane in sync with
the driving pane (formatted pane if visible, else filtered-raw).

## Settings persistence matrix

| Key | Scope | Storage |
|---|---|---|
| Display toggles, alignment, timestamp decimals, field mapping | Global | `PersistentStateComponent` → `JsonlLogViewerSettings.xml` |
| Colors | Per-scheme | IntelliJ Color Scheme system |
| Pane selection, filters, time display | Per-file | `FileEditorState` → project workspace XML |
| Splitter proportion | Global (by key) | `PropertiesComponent` under `JsonlEditor.splitter.proportion` |
| Default pane / time display for first-open files | Global | `PropertiesComponent` under `com.olegs.jsonl.{leftPane|rightPane|timeDisplay}` |

## Live-update path

Any setting change fires `JsonlSettings.notifyChanged()` →
`JsonlSettingsListener.TOPIC.syncPublisher.settingsChanged()`. Every
`JsonlEditor` subscribes in `init` (with `this` as the parent `Disposable`,
so the subscription unregisters automatically on editor close). Listener
calls `applyFiltersNow()` → `rebuildFromSource()` → full pipeline →
document update + span publication → paint.

Color scheme changes bypass the rebuild entirely — `EditorHighlighter.setColorScheme(scheme)` is called by the platform, and the next
`HighlighterIterator.getTextAttributes()` resolves through the new scheme.

## Why the plugin only needs `com.intellij.modules.platform`

Nothing in the plugin references language-specific APIs. JSON syntax
highlighting in the Inspect pane resolves at runtime via
`FileTypeManager.getInstance().getFileTypeByExtension("json")` — if JSON
is not registered (obscure IDE), Inspect falls back to plain text. This
keeps the plugin compatible with every IntelliJ-based IDE: IntelliJ IDEA
(Community + Ultimate), PyCharm, WebStorm, Rider, GoLand, CLion, DataGrip,
RubyMine, RustRover, PhpStorm.
