package io.github.xgl34222220.hetu

/** Small boundary for editor IO; UI verification never needs a live VPN or Root service. */
internal interface ConfigEditorRepository {
    suspend fun load(): ConfigEditSnapshot
    suspend fun validate(text: String)
    suspend fun save(snapshot: ConfigEditSnapshot, text: String)
}

internal class ControllerConfigEditorRepository(private val controller: ProxyComposeController) : ConfigEditorRepository {
    override suspend fun load() = controller.configEditSnapshot()
    override suspend fun validate(text: String) = controller.validateConfigText(text)
    override suspend fun save(snapshot: ConfigEditSnapshot, text: String) = controller.saveConfigSnapshot(snapshot, text)
}
