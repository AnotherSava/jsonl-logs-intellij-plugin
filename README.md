# JSONL Log Viewer

*A plugin for every IntelliJ-based IDE (IDEA, PyCharm, WebStorm, Rider, GoLand, CLion, DataGrip, RubyMine, RustRover, PhpStorm) that renders `.jsonl` (JSON-per-line) structured log files in human-readable form, with highly customizable visualization and a range of filters.*

![Split editor with formatted log lines on the left and JSON inspector on the right](docs/screenshots/main-large.png)

## Features

- **Single or dual panel view** — show Raw and Formatted log lines side-by-side, or focus on one full-width
- **JSON inspector overlay** — caret-driven floating overlay that pretty-prints the entry under the cursor
- **Per-element styling** — coloured severity, italic target, bold message, tinted field keys, dimmed timestamp and `=`
- **Customizable colours** — ten colour keys exposed through the IDE's standard Color Scheme editor
- **Aligned columns** — option to align targets, messages, and fields for improved readability
- **Smart value rendering** — strip the common target prefix, un-wrap Rust Debug `Some(...)` literals, un-escape debug-quoted strings
- **Three composable filters** — severity, target, substring
- **Live stats** — matching entries, first and most recent timestamps
- **Custom field mapping** — defaults to Rust tracing; adapts to pino, Serilog, bunyan, OpenTelemetry, or any other format via five dotted paths
- **Settings menu** — quick toggles for every rendering option

## Install

Build from source — see the [Developer guide](https://anothersava.github.io/jsonl-logs-intellij-plugin/pages/development) for prerequisites and the install-from-disk workflow.

---

See full project documentation at **[anothersava.github.io/jsonl-logs-intellij-plugin](https://anothersava.github.io/jsonl-logs-intellij-plugin/)**:

- [Usage](https://anothersava.github.io/jsonl-logs-intellij-plugin/pages/usage)
  - [Editor layout](https://anothersava.github.io/jsonl-logs-intellij-plugin/pages/usage/editor-layout)
  - [Inspect overlay](https://anothersava.github.io/jsonl-logs-intellij-plugin/pages/usage/inspect-overlay)
  - [Filters](https://anothersava.github.io/jsonl-logs-intellij-plugin/pages/usage/filters)
  - [Stats](https://anothersava.github.io/jsonl-logs-intellij-plugin/pages/usage/stats)
  - [Settings](https://anothersava.github.io/jsonl-logs-intellij-plugin/pages/usage/settings)
  - [Field-mapping reference](https://anothersava.github.io/jsonl-logs-intellij-plugin/pages/usage/field-mapping)
- [Developer guide](https://anothersava.github.io/jsonl-logs-intellij-plugin/pages/development)
  - [Architecture](https://anothersava.github.io/jsonl-logs-intellij-plugin/pages/development/architecture)
  - [Extension points](https://anothersava.github.io/jsonl-logs-intellij-plugin/pages/development/extending)
