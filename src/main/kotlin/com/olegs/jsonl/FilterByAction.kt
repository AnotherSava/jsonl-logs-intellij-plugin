package com.olegs.jsonl

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.fileEditor.FileEditorManager

class FilterByAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val selection = e.getData(CommonDataKeys.EDITOR)?.selectionModel?.selectedText
        val jsonlEditor = findJsonlEditor(e)
        val hasSelection = !selection.isNullOrBlank()
        e.presentation.isEnabledAndVisible = hasSelection && jsonlEditor != null
        if (hasSelection && jsonlEditor != null) {
            val preview = selection!!.trim().take(PREVIEW_LEN).let {
                if (selection.trim().length > PREVIEW_LEN) "$it…" else it
            }
            e.presentation.text = "Filter by \"$preview\""
        }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val raw = e.getData(CommonDataKeys.EDITOR)?.selectionModel?.selectedText ?: return
        // Collapse multi-line selections to the first non-empty line, trimmed —
        // a multi-line substring will match zero entries anyway.
        val text = raw.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        if (text.isEmpty()) return
        findJsonlEditor(e)?.applyTextFilter(text)
    }

    private fun findJsonlEditor(e: AnActionEvent): JsonlEditor? {
        val project = e.project ?: return null
        return FileEditorManager.getInstance(project).selectedEditors
            .firstOrNull { it is JsonlEditor } as? JsonlEditor
    }

    private companion object {
        const val PREVIEW_LEN = 30
    }
}
