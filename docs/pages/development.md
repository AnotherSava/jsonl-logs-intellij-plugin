---
layout: default
title: Development
---

[Home](..) | [Usage](usage) | [Development](development)

---

## Setup

### Prerequisites

- **JDK 17** or newer (the IntelliJ Platform Gradle Plugin 2.x requires 17+).
- **Gradle 8.10+**. The wrapper task in `build.gradle.kts` targets 8.10.2 by default; run `gradle wrapper` to generate the matching wrapper files.

The first build downloads the target IntelliJ IDEA Community distribution (~1 GB) via the IntelliJ Platform Gradle Plugin. Subsequent builds reuse the cached copy.

### Install from source

```
gradle buildPlugin
```

Then in your IDE: **Settings → Plugins → ⚙ → Install plugin from disk…** → select `build/distributions/intellij-jsonl-extension-<version>.zip` → restart.

## Commands

| Task | What it does |
|---|---|
| `gradle test` | Runs the 59+ JUnit 5 unit tests (parser, formatter, rebuilder) |
| `gradle compileKotlin` | Fast syntax + type check without running tests |
| `gradle buildPlugin` | Produces the installable zip at `build/distributions/intellij-jsonl-extension-<version>.zip` |
| `gradle runIde` | Launches a sandbox IntelliJ IDEA with the plugin preloaded. Useful for manual testing |
| `gradle verifyPlugin` | Runs the JetBrains plugin verifier against recommended IDEs (downloads them on first run) |
| `gradle build` | Compile + test + build the plugin zip |

## Architecture

The plugin is built around three principles:

1. **Parse each line exactly once.** Every `.jsonl` line is parsed into a strongly-typed `LogEntry` record by a stateless parser, and every downstream stage (filter, format, stats, alignment width computation) consumes that record — no re-parsing.
2. **Pure logic is Swing-free.** Parsing, formatting, value prettification, prefix detection, and filter predicates are pure Kotlin that depend only on Gson and `java.time`, so they are unit-tested without spinning up the IntelliJ platform.
3. **Let IntelliJ drive the paint cycle.** Highlights are published as sidecar data on the document and read by a custom `EditorHighlighter`. No per-keystroke markup-model churn.

See the [Architecture reference](architecture) for the full domain model, data flow diagram, component-by-component breakdown, settings persistence matrix, and live-update path.

## Project structure

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

## Architecture reference

For internal details — domain model, data-flow diagram, component breakdown, settings persistence matrix, live-update path — see:

- [Architecture reference](architecture)

## Testing

All tests are pure Kotlin — no IntelliJ platform startup required. Tests live under `src/test/kotlin/com/olegs/jsonl/` and are organized by the component they cover:

- `JsonlFormatterTest` — value rendering, timestamp formatting, prefix detection, prettify, padding widths, field mapping edge cases via the compatibility façade.
- `LogEntryParserTest` — field mapping (default / pino / Serilog / empty paths), non-JSON pass-through.
- `JsonlRebuilderTest` — end-to-end rebuild: filter application, prefix stripping, alignment width computation, blank-line behaviour under different filter states, `formattedToRawLine` mapping correctness.

After a structural change to `JsonlSettings.State`, run `gradle clean test` — Kotlin's incremental compile occasionally caches stale synthetic constructor signatures and produces `NoSuchMethodError` at runtime. A clean build resets it.

## Deploying to your own IDE

`gradle runIde` launches a sandbox IDE with the plugin preloaded, which is the fastest way to verify a change. To install the plugin into your real IDE instead, repeat these steps after every rebuild:

1. Quit the running IDE process (`idea64`, `pycharm64`, etc.) — unloading the previous build requires a restart.
2. Rebuild: `gradle buildPlugin`. The artifact lands at `build/distributions/intellij-jsonl-extension-<version>.zip`.
3. Extract the zip into the IDE's `plugins/` directory, overwriting the previous copy:
   - Windows: `%APPDATA%/JetBrains/IntelliJIdea<version>/plugins/`
   - macOS: `~/Library/Application Support/JetBrains/IntelliJIdea<version>/plugins/`
   - Linux: `~/.local/share/JetBrains/IntelliJIdea<version>/plugins/`
4. Relaunch the IDE.

For an automated stop/rebuild/extract/relaunch loop usable from Claude Code, see the [deploy skill](https://github.com/AnotherSava/claude-code-common/tree/main/claude/skills/deploy) in `claude-code-common`.

## Extension points

### Adding a new filter predicate

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

### Adding a new highlight colour

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

### Adding support for a new log format

Usually a pure configuration change: **Settings → Tools → JSONL Log Viewer → Field mapping**. Use dotted paths. See the [Field-mapping reference](usage#field-mapping-reference) for canonical examples.

If your format has exotic semantics (timestamps in an unusual encoding, severity as a number rather than a string, etc.), you may need to extend `LogEntryParser.parseInstant` or introduce an alias table for severity levels. Both are localized to small pieces of `LogEntryParser.kt`.

### Adding a new per-file state field

1. Add a `var` to `JsonlFileEditorState` with a default value. Primitives and enums serialize natively; other types need a `readState`/`writeState` contribution.
2. Write the value in `JsonlEditor.getState()`, apply it in `JsonlEditor.setState()`.
3. Add attribute serialization in `JsonlSplitEditorProvider.writeState` / `readState`.

### Adding a new settings toggle

1. Add a `var` to `JsonlSettings.State` with a default.
2. Expose in `JsonlConfigurable.createPanel()` (one-line `row { checkBox(...).bindSelected(state::name) }`).
3. Consume wherever relevant — usually in `HighlightSpanBuilder` (if it affects rendering) or `JsonlRebuilder`'s use of `FormatConfig`.

## Incremental-compile gotcha

When you change a data class that's heavily used in tests (especially `JsonlSettings.State` — tests call its constructor via named-argument synthetic), Kotlin's incremental compile sometimes keeps the test bytecode against a stale synthetic constructor signature. Symptom: `java.lang.NoSuchMethodError: ‹State›.<init>(…, DefaultConstructorMarker)` at test runtime.

Fix: `gradle clean test` or `gradle --rerun-tasks test`. It's a known Kotlin/Gradle interaction, not a project-specific issue.

## plugin.xml cheat sheet

`src/main/resources/META-INF/plugin.xml` registers:

| Extension | Implementation | Purpose |
|---|---|---|
| `fileType` | `PLAIN_TEXT extensions="jsonl"` | Default `.jsonl` to plain text |
| `fileEditorProvider` | `JsonlSplitEditorProvider` | Hook the split editor on `.jsonl` files |
| `applicationService` | `JsonlSettings` | Global settings |
| `applicationConfigurable` | `JsonlConfigurable` | Settings page |
| `colorSettingsPage` | `JsonlColorSettingsPage` | Color Scheme page |

| Action | Purpose |
|---|---|
| `JsonlLogViewer.FilterBy` | "Filter by selection" context-menu action (added to `EditorPopupMenu` first) |
| `JsonlLogViewer.ViewerPopup` | Custom minimal popup for the read-only viewer panes (Filter by + Copy) |

## Project conventions

- Kotlin UI DSL v2 for Swing-form building (`panel { … }`).
- `LinkedHashMap` when the insertion order of JSON keys matters for display.
- Empty string (not `null`) for "no filter" values, so default equality against `""` works without null-safety noise.
- Data classes for all immutable value records. No getters/setters.
- One `@Service` class (`JsonlSettings`) — everything else is object/data class or class-with-Disposable-parent.
- `Alarm(Alarm.ThreadToUse.SWING_THREAD, this)` for debouncing; `this` is the `FileEditor`, so the alarm cancels when the editor closes.
- `runWriteAction { … }` for every document mutation. `Document.putUserData` inside the same write action to keep token spans in sync with text.
