package com.olegs.jsonl

/**
 * Per-editor-instance filter state. Persisted as part of [JsonlFileEditorState]
 * so each open `.jsonl` file remembers its own filters.
 */
data class JsonlSession(
    val minSeverity: Severity = Severity.ALL,
    val targetFilter: String = "",
    val textFilter: String = "",
) {
    fun isFilterActive(): Boolean =
        minSeverity != Severity.ALL || targetFilter.isNotEmpty() || textFilter.isNotEmpty()
}
