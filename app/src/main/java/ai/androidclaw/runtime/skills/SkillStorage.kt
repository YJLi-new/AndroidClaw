package ai.androidclaw.runtime.skills

import java.io.File

class SkillStorage(
    private val filesDir: File,
    private val cacheDir: File,
) {
    val localSkillsDir: File
        get() = File(filesDir, "skills/local")

    fun workspaceSkillsDir(sessionId: String): File = File(filesDir, "workspaces/$sessionId/skills")

    fun importScratchDir(label: String): File = File(cacheDir, "skill-import-$label")
}
