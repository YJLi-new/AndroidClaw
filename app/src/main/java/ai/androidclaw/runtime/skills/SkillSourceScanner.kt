package ai.androidclaw.runtime.skills

class SkillSourceScanner(
    private val bundledSkillLoader: BundledSkillLoader,
    private val fileSkillLoader: FileSkillLoader,
    private val skillStorage: SkillStorage,
) {
    suspend fun scanBundled(): List<SkillSnapshot> = bundledSkillLoader.load()

    suspend fun scanLocal(): List<SkillSnapshot> =
        fileSkillLoader.load(
            rootDir = skillStorage.localSkillsDir,
            sourceType = SkillSourceType.Local,
        )

    suspend fun scanWorkspace(sessionId: String): List<SkillSnapshot> =
        fileSkillLoader.load(
            rootDir = skillStorage.workspaceSkillsDir(sessionId),
            sourceType = SkillSourceType.Workspace,
            workspaceSessionId = sessionId,
        )
}
