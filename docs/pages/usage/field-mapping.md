---
layout: default
title: Field-mapping reference
parent: Usage
nav_order: 6
---

The plugin defaults to the Rust `tracing_subscriber::fmt::json` layout. For other log libraries, change these five paths under **Settings → Tools → JSONL Log Viewer → Field mapping**. Paths are dot-separated JSON lookups (`fields.message` means "look up `fields`, then `message`"); empty path disables that semantic slot.

| Format | Timestamp | Level | Target | Message | Fields container |
|---|---|---|---|---|---|
| Rust tracing *(default)* | `timestamp` | `level` | `target` | `fields.message` | `fields` |
| pino | `time` | `level` | `name` | `msg` | *(blank)* |
| Serilog | `@t` | `@l` | `SourceContext` | `@mt` | *(blank)* |
| bunyan | `time` | `level` | `name` | `msg` | *(blank)* |
| OpenTelemetry Logs | `timestamp` | `severityText` | `attributes.code.namespace` | `body` | `attributes` |

When `fields container` is blank, every top-level JSON key that isn't consumed by the other four paths is rendered as a `key=value` pair. When it's set, the keys under that object are rendered first, with the leaf consumed by `message` suppressed so you don't see the message duplicated, followed by any other top-level key none of the five paths consumes.

The paths locate values; they do not translate them. A level is recognised only by the names `TRACE`, `DEBUG`, `INFO`, `WARN` and `ERROR`, and a timestamp only as an ISO-8601 string. So pino's and bunyan's numeric levels (`30`, `40`, …) and pino's epoch-millisecond `time` are not interpreted: those entries show no severity colour and no timestamp, and any **Level** threshold other than **All** hides them. Serilog's `Information`, `Verbose` and `Fatal` get no colour, its `Warning` is coloured but not ranked, and an Information event carries no `@l` at all, so a Level threshold hides each of these as well.
