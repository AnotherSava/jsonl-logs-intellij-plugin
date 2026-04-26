---
layout: default
title: Inspect overlay
parent: Usage
nav_order: 2
---

When the right panel is set to Inspect, the left pane fills the editor full-width and the JSON inspector floats over it as a corner-anchored overlay. The caret position in the left pane drives the overlay's content — click any line in the formatted (or raw) pane and the overlay updates to the pretty-printed JSON of that entry. JSON syntax highlighting kicks in if the JSON plugin is available in your IDE (it is in IntelliJ IDEA).

The overlay's chrome lives in the top-right corner: a corner-toggle (↓ / ↑) flips the anchor between top-right and bottom-right, and a close button (×) hides the overlay (equivalent to the toolbar's "Right panel: Off"). Clicking the toolbar's **Inspect** button while it's already selected also flips the corner.

The free corner of the overlay (bottom-left when anchored top, top-left when anchored bottom) carries a resize grip — drag it diagonally to grow or shrink the overlay. When **Auto-resize inspect height** is on, the height is owned by the overlay (it auto-fits the current entry), so the corner grip is hidden and the overlay's left edge becomes a width-only resize handle instead. Size and corner are persisted across sessions.

The line-index ↔ raw-line mapping works correctly across filters: if you filter to 8 `ERROR` entries out of 500 lines, clicking on "Formatted line 3" shows the pretty JSON of the 3rd matching entry — not raw line 3.
