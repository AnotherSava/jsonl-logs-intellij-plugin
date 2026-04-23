package com.olegs.jsonl

import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileEditor.FileEditorStateLevel

/**
 * Per-file editor state. Persisted by IntelliJ on project close (per `VirtualFile`)
 * via [JsonlSplitEditorProvider]'s `readState` / `writeState`.
 *
 * All fields are `var` so the JDOM-based XML serializer can reflectively populate
 * them on load.
 */
class JsonlFileEditorState(
    var leftPane: Pane = Pane.FORMATTED,
    var rightPane: Pane = Pane.INSPECT,
    var minSeverity: Severity = Severity.ALL,
    var targetFilter: String = "",
    var textFilter: String = "",
    var timeDisplay: TimeDisplay = TimeDisplay.RELATIVE,
) : FileEditorState {

    // No-arg constructor is required for readState reflection.
    constructor() : this(Pane.FORMATTED, Pane.INSPECT)

    override fun canBeMergedWith(other: FileEditorState, level: FileEditorStateLevel): Boolean = false

    fun toSession(): JsonlSession = JsonlSession(minSeverity, targetFilter, textFilter)
}
