package com.olegs.jsonl

import com.intellij.icons.AllIcons
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.actionSystem.ex.ComboBoxAction
import com.intellij.openapi.actionSystem.ex.CustomComponentAction
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.ui.JBColor
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorLocation
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileEditor.FileEditorStateLevel
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.fileTypes.UnknownFileType
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.SearchTextField
import com.intellij.util.Alarm
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Font
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.beans.PropertyChangeListener
import java.time.Instant
import javax.swing.event.DocumentEvent as SwingDocumentEvent
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

enum class Pane(val displayName: String, val icon: Icon) {
    RAW("Raw", AllIcons.FileTypes.Json),
    FORMATTED("Formatted", AllIcons.FileTypes.Text),
    INSPECT("Inspect", AllIcons.Actions.Preview),
    NONE("Off", AllIcons.Actions.Cancel),
}

enum class Side(val displayName: String) { LEFT("Left"), RIGHT("Right") }

enum class TimeDisplay { RELATIVE, ABSOLUTE }

// Cascading alignment — each level implies the ones before it.
enum class Alignment(val displayName: String) {
    NONE("None"),
    TARGETS("Targets"),
    MESSAGES("Messages"),
    FIELDS("Fields");

    override fun toString(): String = displayName
}

enum class Severity(val displayName: String, val rank: Int) {
    ALL("All", 0),
    TRACE("Trace", 1),
    DEBUG("Debug", 2),
    INFO("Info", 3),
    WARN("Warn", 4),
    ERROR("Error", 5);

    companion object {
        fun rankOf(level: String): Int = when (level.uppercase()) {
            "TRACE" -> 1
            "DEBUG" -> 2
            "INFO" -> 3
            "WARN" -> 4
            "ERROR" -> 5
            else -> 0
        }
    }
}

class JsonlEditor(
    private val project: Project,
    private val file: VirtualFile,
    private val textEditor: TextEditor,
) : UserDataHolderBase(), FileEditor {

    private val factory = EditorFactory.getInstance()
    private val formattedDoc: Document = factory.createDocument("")
    private val inspectDoc: Document = factory.createDocument("")
    private val filteredRawDoc: Document = factory.createDocument("")
    private val formattedEditor: Editor = factory.createViewer(formattedDoc, project, EditorKind.PREVIEW).withViewerPopup()
    private val inspectEditor: Editor = factory.createEditor(inspectDoc, project, jsonFileType(), true).withViewerPopup()
    private val filteredRawEditor: Editor = factory.createViewer(filteredRawDoc, project, EditorKind.PREVIEW).withViewerPopup()
    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private val rootPanel = JPanel(BorderLayout())
    private val props = PropertiesComponent.getInstance()
    private var leftPane: Pane = loadPane(KEY_LEFT, Pane.FORMATTED).takeUnless { it == Pane.NONE } ?: Pane.FORMATTED
    private var rightPane: Pane = loadPane(KEY_RIGHT, Pane.INSPECT).let { if (it == leftPane) Pane.NONE else it }
    private var lastContent: JComponent? = null
    private var stats: JsonlStats = JsonlStats(0, null, null)
    private var toolbar: ActionToolbar? = null
    private var timeDisplay: TimeDisplay = loadTimeDisplay()
    private var session: JsonlSession = JsonlSession()
    private var allTargets: List<String> = emptyList()
    private var textFilterField: SearchTextField? = null
    private var formattedToRawLine: List<Int> = emptyList()
    private val refreshAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private val filterAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)

    init {
        textEditor.editor.settings.isRightMarginShown = false
        // Attach the sidecar EditorHighlighter to the formatted pane. IntelliJ
        // drives paint through it; our job on each rebuild is to publish a fresh
        // token list via Document.putUserData(JSONL_HIGHLIGHT_SPANS, …).
        (formattedEditor as? EditorEx)?.highlighter = JsonlTokenHighlighter(formattedDoc)
        val sourceDoc = textEditor.editor.document
        rebuildFromSource(sourceDoc.immutableCharSequence.toString())

        sourceDoc.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                val snapshot = event.document.immutableCharSequence.toString()
                alarm.cancelAllRequests()
                alarm.addRequest({
                    rebuildFromSource(snapshot)
                    if (inspectVisible()) refreshInspect()
                }, DEBOUNCE_MS)
            }
        }, this)

        val inspectRefresher = object : CaretListener {
            override fun caretPositionChanged(event: CaretEvent) {
                if (inspectVisible()) refreshInspect()
            }
        }
        formattedEditor.caretModel.addCaretListener(inspectRefresher)
        filteredRawEditor.caretModel.addCaretListener(inspectRefresher)
        textEditor.editor.caretModel.addCaretListener(inspectRefresher)

        val busConnection = ApplicationManager.getApplication().messageBus.connect(this)
        busConnection.subscribe(JsonlSettingsListener.TOPIC, object : JsonlSettingsListener {
            override fun settingsChanged() {
                applyFiltersNow()
            }
        })

        installToolbar()
        applyLayout()

        if (JsonlSettings.getInstance().config.scrollToEndOnOpen) {
            ApplicationManager.getApplication().invokeLater { scrollAllToEnd() }
        }
    }

    private fun scrollAllToEnd() {
        scrollEditorToEnd(textEditor.editor)
        scrollEditorToEnd(formattedEditor)
        scrollEditorToEnd(filteredRawEditor)
    }

    // Move the caret onto the last non-blank line so the Inspect pane (if open)
    // lands on a real entry, and scroll that line into view.
    private fun scrollEditorToEnd(e: Editor) {
        val doc = e.document
        val total = doc.lineCount
        if (total == 0) return
        val text = doc.immutableCharSequence
        var line = total - 1
        while (line > 0) {
            val start = doc.getLineStartOffset(line)
            val end = doc.getLineEndOffset(line)
            if (!text.subSequence(start, end).isBlank()) break
            line--
        }
        e.caretModel.moveToOffset(doc.getLineStartOffset(line))
        e.scrollingModel.scrollTo(LogicalPosition(line, 0), com.intellij.openapi.editor.ScrollType.MAKE_VISIBLE)
    }

    private fun installToolbar() {
        val group = DefaultActionGroup().apply {
            add(GearAction())
            add(SpacerAction())
            add(StaticLabelAction("Left panel: "))
            Pane.entries.filter { it != Pane.NONE }.forEach { add(PickPaneAction(Side.LEFT, it)) }
            add(SpacerAction())
            add(StaticLabelAction("Right panel: "))
            Pane.entries.forEach { add(PickPaneAction(Side.RIGHT, it)) }
            add(SpacerAction())
            add(StaticLabelAction("Level: "))
            add(SeveritySelectorAction())
            add(SpacerAction())
            add(StaticLabelAction("Target: "))
            add(TargetSelectorAction())
            add(SpacerAction())
            add(StaticLabelAction("Filter: "))
            add(TextFilterAction())
            add(SpacerAction())
            add(DynamicLabelAction { "Total entries: ${stats.count}" })
            add(SpacerAction())
            add(ClickableTimestampLabelAction("First entry: ") { stats.firstEntry })
            add(SpacerAction())
            add(ClickableTimestampLabelAction("Most recent: ") { stats.mostRecent })
        }
        val tb = ActionManager.getInstance().createActionToolbar(TOOLBAR_PLACE, group, true)
        tb.targetComponent = rootPanel
        toolbar = tb
        rootPanel.add(tb.component, BorderLayout.NORTH)
        scheduleClockTick()
    }

    private fun toggleTimeDisplay() {
        timeDisplay = if (timeDisplay == TimeDisplay.RELATIVE) TimeDisplay.ABSOLUTE else TimeDisplay.RELATIVE
        props.setValue(KEY_TIME_DISPLAY, timeDisplay.name)
        toolbar?.updateActionsAsync()
    }

    private fun renderTimestamp(prefix: String, instant: Instant?): String {
        if (instant == null) return "$prefix —"
        return when (timeDisplay) {
            TimeDisplay.RELATIVE -> "$prefix ${JsonlFormatter.humanRelative(instant)}"
            TimeDisplay.ABSOLUTE -> "$prefix ${JsonlFormatter.absoluteShort(instant)}"
        }
    }

    private fun scheduleClockTick() {
        refreshAlarm.addRequest({
            toolbar?.updateActionsAsync()
            scheduleClockTick()
        }, CLOCK_TICK_MS)
    }

    private fun select(side: Side, pane: Pane) {
        when (side) {
            Side.LEFT -> {
                leftPane = pane
                if (pane == rightPane) rightPane = Pane.NONE
                if (pane == Pane.INSPECT && rightPane != Pane.RAW) rightPane = Pane.FORMATTED
            }
            Side.RIGHT -> {
                rightPane = pane
                if (pane == Pane.INSPECT && leftPane != Pane.RAW) leftPane = Pane.FORMATTED
            }
        }
        persistPanes()
        applyLayout()
    }

    private fun persistPanes() {
        props.setValue(KEY_LEFT, leftPane.name)
        props.setValue(KEY_RIGHT, rightPane.name)
    }

    private fun applyLayout() {
        lastContent?.let { rootPanel.remove(it) }
        val left = componentFor(leftPane)
        val right = componentFor(rightPane)
        val content: JComponent = when {
            left != null && right != null -> OnePixelSplitter(false, SPLITTER_PROPORTION_KEY, 0.5f).apply {
                firstComponent = left
                secondComponent = right
            }
            left != null -> left
            right != null -> right
            else -> formattedEditor.component  // unreachable: select() prevents both-NONE
        }
        if (inspectVisible()) {
            ensureDrivingCaretValid()
            refreshInspect()
        }
        rootPanel.add(content, BorderLayout.CENTER)
        lastContent = content
        rootPanel.revalidate()
        rootPanel.repaint()
    }

    // Inspect needs a "current line" from the driving pane. If the driving editor's
    // caret is out of range (e.g. document shorter than last known position), park it
    // at line 0 so Inspect always has something meaningful to show.
    private fun ensureDrivingCaretValid() {
        val driving = drivingEditor() ?: return
        val line = driving.caretModel.logicalPosition.line
        val lineCount = driving.document.lineCount
        if (line < 0 || line >= lineCount.coerceAtLeast(1)) {
            driving.caretModel.moveToLogicalPosition(LogicalPosition(0, 0))
        }
    }

    private fun drivingEditor(): Editor? {
        val formattedVisible = leftPane == Pane.FORMATTED || rightPane == Pane.FORMATTED
        val rawVisible = leftPane == Pane.RAW || rightPane == Pane.RAW
        return when {
            formattedVisible -> formattedEditor
            rawVisible -> filteredRawEditor
            else -> null
        }
    }

    private fun componentFor(pane: Pane): JComponent? = when (pane) {
        // Always show the synthetic viewer. The real textEditor stays alive in the
        // background for document listening + FileDocumentManager plumbing, but
        // the UI never presents it directly — that way caret/scroll/selection in
        // the raw pane survive filter toggles.
        Pane.RAW -> filteredRawEditor.component
        Pane.FORMATTED -> formattedEditor.component
        Pane.INSPECT -> inspectEditor.component
        Pane.NONE -> null
    }

    private fun inspectVisible(): Boolean = leftPane == Pane.INSPECT || rightPane == Pane.INSPECT

    private fun rebuildFromSource(source: String) {
        val cfg = JsonlSettings.getInstance().config
        val result = JsonlRebuilder.rebuild(source, session, cfg)
        allTargets = result.allTargets
        formattedToRawLine = result.formattedToRawLine
        stats = result.stats

        val formattedText = result.structuredLines.joinToString("\n") { it.text }
        val spans = HighlightSpanBuilder.build(result.structuredLines, cfg)

        ApplicationManager.getApplication().runWriteAction {
            formattedDoc.setText(formattedText)
            formattedDoc.putUserData(JSONL_HIGHLIGHT_SPANS, spans)
            filteredRawDoc.setText(result.rawLines.joinToString("\n"))
        }
        toolbar?.updateActionsAsync()
    }

    private fun filterActive(): Boolean = session.isFilterActive()


    private fun applyFiltersNow() {
        val snapshot = textEditor.editor.document.immutableCharSequence.toString()
        rebuildFromSource(snapshot)
        applyLayout()
    }

    private fun scheduleApplyFilters() {
        filterAlarm.cancelAllRequests()
        filterAlarm.addRequest({ applyFiltersNow() }, FILTER_DEBOUNCE_MS)
    }

    // Line driving Inspect: prefer formatted caret when formatted is visible,
    // otherwise fall back to raw caret so Raw|Inspect works too.
    private fun inspectedLine(): Int =
        drivingEditor()?.caretModel?.logicalPosition?.line ?: 0

    private fun refreshInspect() {
        val line = inspectedLine()
        val rawDoc = textEditor.editor.document
        // Every visible pane is now a filtered view — translate the driver's line
        // index back to the original raw-line index via the rebuilder mapping.
        val rawIdx = formattedToRawLine.getOrNull(line)
        if (rawIdx == null || rawIdx < 0 || rawIdx >= rawDoc.lineCount) {
            setInspectText("")
            return
        }
        val start = rawDoc.getLineStartOffset(rawIdx)
        val end = rawDoc.getLineEndOffset(rawIdx)
        val lineText = rawDoc.immutableCharSequence.subSequence(start, end).toString()
        setInspectText(JsonlFormatter.prettyJson(lineText))
    }

    private fun setInspectText(text: String) {
        ApplicationManager.getApplication().runWriteAction {
            inspectDoc.setText(text)
            inspectEditor.caretModel.moveToLogicalPosition(LogicalPosition(0, 0))
        }
    }

    private class StaticLabelAction(private val text: String) : AnAction(), CustomComponentAction {
        override fun createCustomComponent(presentation: Presentation, place: String): JComponent =
            JLabel(text).apply { border = JBUI.Borders.empty(0, 4, 0, 2) }
        override fun updateCustomComponent(component: JComponent, presentation: Presentation) {}
        override fun actionPerformed(e: AnActionEvent) {}
    }

    private class DynamicLabelAction(private val textSupplier: () -> String) : AnAction(), CustomComponentAction {
        override fun createCustomComponent(presentation: Presentation, place: String): JComponent =
            JLabel(textSupplier()).apply { border = JBUI.Borders.empty(0, 4, 0, 2) }
        override fun updateCustomComponent(component: JComponent, presentation: Presentation) {
            (component as JLabel).text = textSupplier()
        }
        override fun actionPerformed(e: AnActionEvent) {}
        override fun update(e: AnActionEvent) {
            e.presentation.text = textSupplier()
        }
    }

    private class SpacerAction : AnAction(), CustomComponentAction {
        override fun createCustomComponent(presentation: Presentation, place: String): JComponent =
            JPanel().apply {
                isOpaque = false
                preferredSize = JBUI.size(24, 1)
                maximumSize = JBUI.size(24, Int.MAX_VALUE)
            }
        override fun updateCustomComponent(component: JComponent, presentation: Presentation) {}
        override fun actionPerformed(e: AnActionEvent) {}
    }

    private inner class SeveritySelectorAction : ComboBoxAction() {
        override fun createPopupActionGroup(button: JComponent): DefaultActionGroup {
            val group = DefaultActionGroup()
            Severity.entries.forEach { group.add(PickSeverityAction(it)) }
            return group
        }

        override fun update(e: AnActionEvent) {
            super.update(e)
            e.presentation.text = session.minSeverity.displayName
            e.presentation.description = "Hide entries below this severity level"
        }
    }

    private inner class PickSeverityAction(private val severity: Severity) :
        ToggleAction(severity.displayName) {
        override fun isSelected(e: AnActionEvent): Boolean = session.minSeverity == severity
        override fun setSelected(e: AnActionEvent, state: Boolean) {
            if (state) {
                session = session.copy(minSeverity = severity)
                applyFiltersNow()
            }
        }
    }

    private inner class TargetSelectorAction : ComboBoxAction() {
        override fun createPopupActionGroup(button: JComponent): DefaultActionGroup {
            val group = DefaultActionGroup()
            group.add(PickTargetAction("(All)", ""))
            allTargets.forEach { t -> group.add(PickTargetAction(t, t)) }
            return group
        }

        override fun update(e: AnActionEvent) {
            super.update(e)
            e.presentation.text = if (session.targetFilter.isEmpty()) "(All)" else session.targetFilter
            e.presentation.description = "Filter entries by target module"
        }
    }

    private inner class PickTargetAction(label: String, private val value: String) :
        ToggleAction(label) {
        override fun isSelected(e: AnActionEvent): Boolean = session.targetFilter == value
        override fun setSelected(e: AnActionEvent, state: Boolean) {
            if (state) {
                session = session.copy(targetFilter = value)
                applyFiltersNow()
            }
        }
    }

    private inner class TextFilterAction : AnAction(), CustomComponentAction {
        override fun createCustomComponent(presentation: Presentation, place: String): JComponent {
            val field = SearchTextField()
            field.toolTipText = "Filter log entries by text (case-insensitive, matches raw JSON or formatted text)"
            field.text = session.textFilter
            field.textEditor.document.addDocumentListener(object : DocumentAdapter() {
                override fun textChanged(e: SwingDocumentEvent) {
                    session = session.copy(textFilter = field.text)
                    scheduleApplyFilters()
                }
            })
            val height = field.preferredSize.height.coerceAtLeast(FILTER_HEIGHT_PX)
            field.preferredSize = JBUI.size(FILTER_WIDTH_PX, height)
            textFilterField = field
            return field
        }

        override fun updateCustomComponent(component: JComponent, presentation: Presentation) {}
        override fun actionPerformed(e: AnActionEvent) {}
    }

    // Called from the "Filter by" context-menu action. Sets the filter text box and
    // runs the filter immediately (skipping the debounce).
    fun applyTextFilter(text: String) {
        session = session.copy(textFilter = text)
        textFilterField?.text = text
        applyFiltersNow()
    }

    private inner class GearAction : AnAction("JSONL settings", "JSONL log viewer settings", AllIcons.General.GearPlain) {
        override fun actionPerformed(e: AnActionEvent) {
            val component = e.inputEvent?.component ?: return
            val group = DefaultActionGroup().apply {
                add(settingToggle("Strip common prefix") { it::stripCommonPrefix })
                add(settingToggle("Colour severity label") { it::colourSeverity })
                add(settingToggle("Highlight field names") { it::highlightFieldNames })
                add(settingToggle("Bold message text") { it::boldMessage })
                add(settingToggle("Italic target") { it::italicTarget })
                add(settingToggle("Dim timestamp and '='") { it::dimTimestampAndEquals })
                add(settingToggle("Prettify values") { it::prettifyValues })
                addSeparator("Align")
                Alignment.entries.forEach { a ->
                    add(object : ToggleAction(a.displayName) {
                        override fun isSelected(e: AnActionEvent): Boolean =
                            JsonlSettings.getInstance().config.alignment == a
                        override fun setSelected(e: AnActionEvent, state: Boolean) {
                            if (state) {
                                JsonlSettings.getInstance().config.alignment = a
                                JsonlSettings.getInstance().notifyChanged()
                            }
                        }
                    })
                }
                addSeparator()
                add(settingToggle("Scroll to latest on open") { it::scrollToEndOnOpen })
                addSeparator()
                add(object : AnAction("Open full settings…") {
                    override fun actionPerformed(e: AnActionEvent) {
                        ShowSettingsUtil.getInstance().showSettingsDialog(
                            project,
                            JsonlConfigurable::class.java,
                        )
                    }
                })
            }
            val popup = JBPopupFactory.getInstance().createActionGroupPopup(
                "JSONL Settings",
                group,
                e.dataContext,
                JBPopupFactory.ActionSelectionAid.SPEEDSEARCH,
                true,
            )
            popup.showUnderneathOf(component)
        }
    }

    private fun settingToggle(
        label: String,
        selector: (JsonlSettings.State) -> kotlin.reflect.KMutableProperty0<Boolean>,
    ): ToggleAction = object : ToggleAction(label) {
        private val prop get() = selector(JsonlSettings.getInstance().config)
        override fun isSelected(e: AnActionEvent): Boolean = prop.get()
        override fun setSelected(e: AnActionEvent, state: Boolean) {
            prop.set(state)
            JsonlSettings.getInstance().notifyChanged()
        }
    }

    private inner class ClickableTimestampLabelAction(
        private val prefix: String,
        private val instantSupplier: () -> Instant?,
    ) : AnAction(), CustomComponentAction {
        override fun createCustomComponent(presentation: Presentation, place: String): JComponent =
            JLabel(renderTimestamp(prefix, instantSupplier())).apply {
                border = JBUI.Borders.empty(0, 4, 0, 2)
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                toolTipText = "Click to switch between relative and absolute time"
                addMouseListener(object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent) {
                        toggleTimeDisplay()
                    }
                })
            }

        override fun updateCustomComponent(component: JComponent, presentation: Presentation) {
            (component as JLabel).text = renderTimestamp(prefix, instantSupplier())
        }

        override fun actionPerformed(e: AnActionEvent) {}
        override fun update(e: AnActionEvent) {
            e.presentation.text = renderTimestamp(prefix, instantSupplier())
        }
    }

    private inner class PickPaneAction(private val side: Side, private val pane: Pane) :
        ToggleAction("${pane.displayName} on ${side.displayName.lowercase()}", tooltipFor(side, pane), pane.icon) {
        override fun isSelected(e: AnActionEvent): Boolean =
            (if (side == Side.LEFT) leftPane else rightPane) == pane

        override fun setSelected(e: AnActionEvent, state: Boolean) {
            if (state) select(side, pane)
        }

        override fun update(e: AnActionEvent) {
            super.update(e)
            // Right-side buttons are disabled for whichever mode is active on the left.
            if (side == Side.RIGHT) {
                e.presentation.isEnabled = leftPane != pane
            }
        }
    }

    override fun getComponent(): JComponent = rootPanel
    override fun getPreferredFocusedComponent(): JComponent =
        componentFor(leftPane) ?: componentFor(rightPane) ?: formattedEditor.contentComponent
    override fun getName(): String = "JSONL"
    override fun getFile(): VirtualFile = file
    override fun getState(level: FileEditorStateLevel): FileEditorState = JsonlFileEditorState(
        leftPane = leftPane,
        rightPane = rightPane,
        minSeverity = session.minSeverity,
        targetFilter = session.targetFilter,
        textFilter = session.textFilter,
        timeDisplay = timeDisplay,
    )

    override fun setState(state: FileEditorState) {
        if (state !is JsonlFileEditorState) return
        leftPane = state.leftPane
        rightPane = state.rightPane
        timeDisplay = state.timeDisplay
        session = state.toSession()
        textFilterField?.text = state.textFilter
        applyFiltersNow()
    }
    override fun isModified(): Boolean = textEditor.isModified
    override fun isValid(): Boolean = textEditor.isValid
    override fun addPropertyChangeListener(listener: PropertyChangeListener) = textEditor.addPropertyChangeListener(listener)
    override fun removePropertyChangeListener(listener: PropertyChangeListener) = textEditor.removePropertyChangeListener(listener)
    override fun getCurrentLocation(): FileEditorLocation? = textEditor.currentLocation

    override fun dispose() {
        factory.releaseEditor(formattedEditor)
        factory.releaseEditor(inspectEditor)
        factory.releaseEditor(filteredRawEditor)
        Disposer.dispose(textEditor)
    }

    private companion object {
        const val DEBOUNCE_MS = 100
        const val FILTER_DEBOUNCE_MS = 200
        const val CLOCK_TICK_MS = 30_000
        const val FILTER_WIDTH_PX = 200
        const val FILTER_HEIGHT_PX = 28
        val LAYER_KEYS: Int = HighlighterLayer.ADDITIONAL_SYNTAX

        const val TOOLBAR_PLACE = "JsonlEditorToolbar"
        const val SPLITTER_PROPORTION_KEY = "JsonlEditor.splitter.proportion"
        const val KEY_LEFT = "com.olegs.jsonl.leftPane"
        const val KEY_RIGHT = "com.olegs.jsonl.rightPane"
        const val KEY_TIME_DISPLAY = "com.olegs.jsonl.timeDisplay"

        fun loadPane(key: String, default: Pane): Pane {
            val stored = PropertiesComponent.getInstance().getValue(key) ?: return default
            return Pane.entries.find { it.name == stored } ?: default
        }

        fun loadTimeDisplay(): TimeDisplay {
            val stored = PropertiesComponent.getInstance().getValue(KEY_TIME_DISPLAY) ?: return TimeDisplay.RELATIVE
            return TimeDisplay.entries.find { it.name == stored } ?: TimeDisplay.RELATIVE
        }

        fun jsonFileType(): FileType {
            val type = FileTypeManager.getInstance().getFileTypeByExtension("json")
            return if (type is UnknownFileType) PlainTextFileType.INSTANCE else type
        }

        // Viewer editors have an empty/Copy-only context menu by default. Point them
        // at our minimal viewer popup (Filter-by + Copy) — we don't want the full
        // EditorPopupMenu with edit/refactor/navigate since these panes are read-only.
        // Also turn off the right-margin vertical guide — it's a code-wrap hint that
        // makes no sense for log files.
        private fun Editor.withViewerPopup(): Editor {
            (this as? EditorEx)?.setContextMenuGroupId("JsonlLogViewer.ViewerPopup")
            settings.isRightMarginShown = false
            return this
        }

        fun tooltipFor(side: Side, pane: Pane): String {
            val sideWord = side.displayName.lowercase()
            return when (pane) {
                Pane.RAW -> "Show the raw .jsonl file on the $sideWord"
                Pane.FORMATTED -> "Show the formatted log on the $sideWord"
                Pane.INSPECT -> "Show pretty-printed JSON of the current line on the $sideWord"
                Pane.NONE -> "Hide the $sideWord pane"
            }
        }
    }
}
