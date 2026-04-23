# Development

## Prerequisites

- **JDK 17** or newer (the IntelliJ Platform Gradle Plugin 2.x requires 17+).
- **Gradle 8.5+**. The project is verified on 8.5; 8.10+ works if you bump
  the wrapper via `gradle wrapper --gradle-version 8.10.x`.

The first build downloads the target IntelliJ IDEA Community distribution
(~1 GB) via the IntelliJ Platform Gradle Plugin. Subsequent builds reuse
the cached copy.

## Common Gradle tasks

| Task | What it does |
|---|---|
| `gradle test` | Runs the 59+ JUnit 5 unit tests (parser, formatter, rebuilder) |
| `gradle compileKotlin` | Fast syntax + type check without running tests |
| `gradle buildPlugin` | Produces the installable zip at `build/distributions/intellij-jsonl-extension-<version>.zip` |
| `gradle runIde` | Launches a sandbox IntelliJ IDEA with the plugin preloaded. Useful for manual testing |
| `gradle verifyPlugin` | Runs the JetBrains plugin verifier against recommended IDEs (downloads them on first run) |
| `gradle build` | Compile + test + build the plugin zip |

`--no-daemon` is the project default for CI-style reliability. Daemon mode
works fine for iterative local development.

## Running tests

All pure Kotlin, no IntelliJ platform startup needed. Tests live under
`src/test/kotlin/com/olegs/jsonl/` and are organized by the component they
cover:

- `JsonlFormatterTest` — value rendering, timestamp formatting, prefix
  detection, prettify, padding widths, field mapping edge cases via the
  compatibility façade.
- `LogEntryParserTest` — field mapping (default / pino / Serilog / empty
  paths), non-JSON pass-through.
- `JsonlRebuilderTest` — end-to-end rebuild: filter application, prefix
  stripping, alignment width computation, blank-line behaviour under
  different filter states, `formattedToRawLine` mapping correctness.

After a structural change to `JsonlSettings.State`, run
`gradle clean test` — Kotlin's incremental compile occasionally caches
stale synthetic constructor signatures and produces `NoSuchMethodError` at
runtime. A clean build resets it.

## Deploying to your own IDE

This repo includes a `scripts/deploy.sh` wrapper that:

1. Stops the configured IDE process.
2. Rebuilds the plugin.
3. Extracts the new zip over the existing copy in the IDE's `plugins/` folder.
4. Relaunches the IDE.

Configuration lives in `config/deploy.env`:

```
INSTALL_DIR=%APPDATA%/JetBrains/IntelliJIdea2026.1/plugins
IDE_PROCESS=idea64
IDE_EXE=<path to idea64.exe>
```

`%APPDATA%` is expanded at deploy time. The deploy script is invoked via
the skill in `~/.claude/skills/deploy/scripts/deploy-intellij-plugin.sh` —
inspect that script for the full argument handling.

Run via `bash scripts/deploy.sh` or `! deploy` in a shell where the
`deploy` function is in `~/.bashrc`.

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
3. Include it in `JsonlRebuilder`'s chain construction (conditionally, keyed
   on whether the value is the sentinel "inactive" value).
4. Update `isFilterActive()` to account for the new filter.
5. Add UI in `JsonlEditor.installToolbar()` — either a `ComboBoxAction`
   (like `TargetSelectorAction`) or a `SearchTextField`-style control.
6. Persist it in `JsonlFileEditorState` + provider's `readState`/`writeState`.
7. Add a test in `JsonlRebuilderTest`.

### Adding a new highlight colour

1. Declare the key in `JsonlColors.kt`:
   ```kotlin
   val SPAN_ID: TextAttributesKey = TextAttributesKey.createTextAttributesKey(
       "JSONL_SPAN_ID", DefaultLanguageHighlighterColors.METADATA
   )
   ```
2. Register it with the Color Scheme page — add an `AttributesDescriptor`
   in `JsonlColorSettingsPage.kt` and a tag in `getAdditionalHighlightingTagToDescriptorMap()`.
3. Have the Formatter emit a range for the token (add a new `IntRange?` on
   `FormattedLine`, populate it in `Formatter`).
4. Teach `HighlightSpanBuilder.collectLineSpans` to emit a span for the new
   range with the new key.

No markup-model changes needed — the sidecar highlighter picks up the new
span type automatically.

### Adding support for a new log format

Usually a pure configuration change: **Settings → Tools → JSONL Log Viewer
→ Field mapping**. Use dotted paths. See
[user-guide.md § Field-mapping reference](user-guide.md#field-mapping-reference)
for canonical examples.

If your format has exotic semantics (timestamps in an unusual encoding,
severity as a number rather than a string, etc.), you may need to extend
`LogEntryParser.parseInstant` or introduce an alias table for severity
levels. Both are localized to small pieces of `LogEntryParser.kt`.

### Adding a new per-file state field

1. Add a `var` to `JsonlFileEditorState` with a default value. Primitives
   and enums serialize natively; other types need a `readState`/`writeState`
   contribution.
2. Write the value in `JsonlEditor.getState()`, apply it in
   `JsonlEditor.setState()`.
3. Add attribute serialization in `JsonlSplitEditorProvider.writeState` /
   `readState`.

### Adding a new settings toggle

1. Add a `var` to `JsonlSettings.State` with a default.
2. Expose in `JsonlConfigurable.createPanel()` (one-line `row { checkBox(...).bindSelected(state::name) }`).
3. Consume wherever relevant — usually in `HighlightSpanBuilder` (if it
   affects rendering) or `JsonlRebuilder`'s use of `FormatConfig`.

## Incremental-compile gotcha

When you change a data class that's heavily used in tests (especially
`JsonlSettings.State` — tests call its constructor via named-argument
synthetic), Kotlin's incremental compile sometimes keeps the test bytecode
against a stale synthetic constructor signature. Symptom:
`java.lang.NoSuchMethodError: ‹State›.<init>(…, DefaultConstructorMarker)`
at test runtime.

Fix: `gradle clean test` or `gradle --rerun-tasks test`. It's a known
Kotlin/Gradle interaction, not a project-specific issue.

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
- Empty string (not `null`) for "no filter" values, so default equality against
  `""` works without null-safety noise.
- Data classes for all immutable value records. No getters/setters.
- One `@Service` class (`JsonlSettings`) — everything else is object/data
  class or class-with-Disposable-parent.
- `Alarm(Alarm.ThreadToUse.SWING_THREAD, this)` for debouncing; `this` is the
  `FileEditor`, so the alarm cancels when the editor closes.
- `runWriteAction { … }` for every document mutation. `Document.putUserData`
  inside the same write action to keep token spans in sync with text.
