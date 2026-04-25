package com.olegs.jsonl

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.messages.Topic

interface JsonlSettingsListener {
    fun settingsChanged()

    companion object {
        val TOPIC: Topic<JsonlSettingsListener> =
            Topic("JSONL settings changed", JsonlSettingsListener::class.java)
    }
}

@State(name = "JsonlLogViewerSettings", storages = [Storage("JsonlLogViewerSettings.xml")])
@Service(Service.Level.APP)
class JsonlSettings : PersistentStateComponent<JsonlSettings.State> {

    data class State(
        /** Schema version. Increment when the shape of this class changes so
         *  migrations in [loadState] can fire. */
        var schemaVersion: Int = 1,

        // Display toggles.
        var stripCommonPrefix: Boolean = true,
        var colourSeverity: Boolean = true,
        var highlightFieldNames: Boolean = true,
        var boldMessage: Boolean = true,
        var italicTarget: Boolean = true,
        var dimTimestampAndEquals: Boolean = true,
        var prettifyValues: Boolean = true,

        // Layout.
        var alignment: Alignment = Alignment.TARGETS,

        // Value formatting.
        var timestampDecimals: Int = 0,

        // Behaviour.
        var scrollToEndOnOpen: Boolean = false,
        var autoResizeInspect: Boolean = false,

        // Field mapping — dotted JSON paths, one per semantic slot.
        var fieldTimestampPath: String = FieldMapping.DEFAULT.timestampPath,
        var fieldLevelPath: String = FieldMapping.DEFAULT.levelPath,
        var fieldTargetPath: String = FieldMapping.DEFAULT.targetPath,
        var fieldMessagePath: String = FieldMapping.DEFAULT.messagePath,
        var fieldFieldsPath: String = FieldMapping.DEFAULT.fieldsPath,
    ) {
        fun fieldMapping(): FieldMapping = FieldMapping(
            timestampPath = fieldTimestampPath,
            levelPath = fieldLevelPath,
            targetPath = fieldTargetPath,
            messagePath = fieldMessagePath,
            fieldsPath = fieldFieldsPath,
        )
    }

    private var backing: State = State()
    val config: State get() = backing

    override fun getState(): State = backing
    override fun loadState(newState: State) {
        backing = migrate(newState)
    }

    fun notifyChanged() {
        ApplicationManager.getApplication().messageBus
            .syncPublisher(JsonlSettingsListener.TOPIC)
            .settingsChanged()
    }

    /** Placeholder for future schema migrations. v0 (pre-version-field) loaded
     *  XML silently dropped removed fields — defaults already take over. */
    private fun migrate(loaded: State): State {
        if (loaded.schemaVersion < 1) loaded.schemaVersion = 1
        return loaded
    }

    companion object {
        fun getInstance(): JsonlSettings =
            ApplicationManager.getApplication().getService(JsonlSettings::class.java)
    }
}
