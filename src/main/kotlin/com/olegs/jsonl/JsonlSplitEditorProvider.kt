package com.olegs.jsonl

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import org.jdom.Element

class JsonlSplitEditorProvider : FileEditorProvider, DumbAware {

    override fun accept(project: Project, file: VirtualFile): Boolean =
        !file.isDirectory && file.extension.equals(JSONL_EXTENSION, ignoreCase = true)

    override fun createEditor(project: Project, file: VirtualFile): FileEditor {
        val document = FileDocumentManager.getInstance().getDocument(file)
            ?: error("Cannot open ${file.presentableUrl}: no backing document")
        val textEditor = TextEditorProvider.getInstance().createEditor(project, file) as TextEditor
        return JsonlEditor(project, file, textEditor)
    }

    override fun getEditorTypeId(): String = EDITOR_TYPE_ID

    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR

    override fun readState(sourceElement: Element, project: Project, file: VirtualFile): FileEditorState {
        val state = JsonlFileEditorState()
        state.leftPane = readEnum<Pane>(sourceElement, "leftPane") ?: state.leftPane
        state.rightPane = readEnum<Pane>(sourceElement, "rightPane") ?: state.rightPane
        state.minSeverity = readEnum<Severity>(sourceElement, "minSeverity") ?: state.minSeverity
        state.timeDisplay = readEnum<TimeDisplay>(sourceElement, "timeDisplay") ?: state.timeDisplay
        state.targetFilter = sourceElement.getAttributeValue("targetFilter").orEmpty()
        state.textFilter = sourceElement.getAttributeValue("textFilter").orEmpty()
        return state
    }

    override fun writeState(state: FileEditorState, project: Project, targetElement: Element) {
        if (state !is JsonlFileEditorState) return
        targetElement.setAttribute("leftPane", state.leftPane.name)
        targetElement.setAttribute("rightPane", state.rightPane.name)
        targetElement.setAttribute("minSeverity", state.minSeverity.name)
        targetElement.setAttribute("timeDisplay", state.timeDisplay.name)
        if (state.targetFilter.isNotEmpty()) targetElement.setAttribute("targetFilter", state.targetFilter)
        if (state.textFilter.isNotEmpty()) targetElement.setAttribute("textFilter", state.textFilter)
    }

    private inline fun <reified E : Enum<E>> readEnum(el: Element, attr: String): E? {
        val raw = el.getAttributeValue(attr) ?: return null
        return enumValues<E>().firstOrNull { it.name == raw }
    }

    private companion object {
        const val JSONL_EXTENSION = "jsonl"
        const val EDITOR_TYPE_ID = "jsonl-split-editor"
    }
}
