# JSONL Log Viewer

*An IntelliJ Platform plugin that renders `.jsonl` (JSON-per-line) structured log files as human-readable log lines in a side-by-side split view, with filters, highlighting, and a JSON inspector for the current entry.*

![JSONL Log Viewer](docs/screenshots/main-large.png)

Turn any `.jsonl` file into a readable log stream without post-processing. Each line becomes `<local-timestamp> <level> <target>: <message> <k=v> ...` with severity colours, aligned targets, and a live caret-driven inspector showing the pretty-printed JSON of the current entry. Custom highlighting, per-file filters, and per-project field mappings keep the view calibrated to whatever log library is writing the file — Rust tracing, pino, Serilog, bunyan, OpenTelemetry, or any other JSON-per-line format. Works in every IntelliJ-based IDE (IntelliJ IDEA, PyCharm, WebStorm, Rider, GoLand, CLion, DataGrip, …) because it only depends on `com.intellij.modules.platform`.

## [Usage](https://anothersava.github.io/jsonl-logs-intellij-plugin/pages/usage)

Open any `.jsonl` file and the plugin renders it as a two-pane split editor. Each pane independently shows the raw JSON, formatted log lines, or a pretty-printed JSON inspector that tracks the caret in the other pane. Three independent filters (severity threshold, exact target, case-insensitive substring) compose freely; right-click selection to **Filter by**. A gear popup provides quick toggles for every rendering option, and ten semantic colour keys are exposed through the IDE's **Color Scheme** editor.

## Install

Build from source — see the [Developer guide](https://anothersava.github.io/jsonl-logs-intellij-plugin/pages/development) for prerequisites and the install-from-disk workflow.

---

See full project documentation at **[anothersava.github.io/jsonl-logs-intellij-plugin](https://anothersava.github.io/jsonl-logs-intellij-plugin/)**:

- [Installation and usage](https://anothersava.github.io/jsonl-logs-intellij-plugin/)
  - [Usage](https://anothersava.github.io/jsonl-logs-intellij-plugin/pages/usage)
- [Developer guide](https://anothersava.github.io/jsonl-logs-intellij-plugin/pages/development)
