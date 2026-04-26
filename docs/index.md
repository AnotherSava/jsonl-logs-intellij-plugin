---
layout: default
title: Home
nav_order: 1
---

*A plugin for every IntelliJ-based IDE (IDEA, PyCharm, WebStorm, Rider, GoLand, CLion, DataGrip, RubyMine, RustRover, PhpStorm) that renders `.jsonl` (JSON-per-line) structured log files in human-readable form, with highly customizable visualization and a range of filters.*

<a href="screenshots/main-large.png"><img src="screenshots/main-large.png" alt="Split editor with formatted log lines on the left and JSON inspector on the right" width="1000"></a>

## Example

Given an entry like:

```json
{
  "timestamp": "2026-04-22T21:43:38.033084Z",
  "level": "DEBUG",
  "fields": {
    "message": "event -> clear",
    "client": "claude",
    "event": "SessionEnd",
    "chat_id": "zed-ext"
  },
  "target": "ai_agent_dashboard_lib::http_server"
}
```

the plugin displays it as:

<pre><code><span style="color: #8C8C8C">2026-04-22 21:43:38</span> <span style="color: #8C8C8C">DEBUG</span> <em>http_server</em>: <strong style="color: #067D17">event -&gt; clear</strong> <span style="color: #871094">client</span>=claude <span style="color: #871094">event</span>=SessionEnd <span style="color: #871094">chat_id</span>=zed-ext</code></pre>

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

Build the plugin from source — see the [Developer guide](pages/development) for JDK / Gradle prerequisites and the install-from-disk workflow.

## Next steps

- **[Usage](pages/usage)** — pane layouts, filter behaviour, the Inspect overlay, gear popup, settings page, Color Scheme integration, and field-mapping reference for non-Rust log formats
- **[Developer guide](pages/development)** — building from source, running tests, debugging in a sandbox IDE
