package io.github.xgl34222220.hetu

/** Small boundary for editor IO; UI verification never needs a live VPN or Root service. */
internal interface ConfigEditorRepository {
    suspend fun load(): ConfigEditSnapshot
    suspend fun validate(text: String)
    suspend fun save(snapshot: ConfigEditSnapshot, text: String): ProxyConfigLibrary.SourceVersion

    /** A failed write is only a conflict when the actual selected source has changed. */
    suspend fun conflictAfterFailure(opened: ConfigEditSnapshot): ConfigEditorConflict? {
        val current = load()
        val changedSelection = opened.coreId != current.coreId || opened.name != current.name
        return if (changedSelection || opened.originalText != current.originalText) {
            ConfigEditorConflict(current.name, changedSelection)
        } else null
    }
}

internal data class ConfigEditorConflict(val currentName: String, val changedSelection: Boolean)

internal class ControllerConfigEditorRepository(private val controller: ProxyComposeController) : ConfigEditorRepository {
    override suspend fun load() = controller.configEditSnapshot()
    override suspend fun validate(text: String) = controller.validateConfigText(text)
    override suspend fun save(snapshot: ConfigEditSnapshot, text: String) = controller.saveConfigSnapshot(snapshot, text)
}
