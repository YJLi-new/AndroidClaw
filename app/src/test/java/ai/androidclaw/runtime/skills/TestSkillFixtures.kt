package ai.androidclaw.runtime.skills

import ai.androidclaw.data.repository.SkillRepository
import ai.androidclaw.runtime.tools.ToolDescriptor
import ai.androidclaw.testutil.InMemorySkillConfigStore
import ai.androidclaw.testutil.InMemorySkillSecretStore
import android.app.Application
import java.io.File
import java.util.UUID

fun createTestSkillManager(
    application: Application,
    bundledSkillLoader: BundledSkillLoader =
        BundledSkillLoader(
            assetManager = application.assets,
            rootPath = "skills",
            parser = SkillParser(),
        ),
    skillRepository: SkillRepository,
    toolDescriptor: (String) -> ToolDescriptor?,
    skillConfigStore: InMemorySkillConfigStore = InMemorySkillConfigStore(),
    skillSecretStore: InMemorySkillSecretStore = InMemorySkillSecretStore(),
): SkillManager {
    val rootDir =
        File(application.cacheDir, "skill-tests/${UUID.randomUUID()}").apply {
            mkdirs()
        }
    val skillStorage =
        SkillStorage(
            filesDir = File(rootDir, "files").apply { mkdirs() },
            cacheDir = File(rootDir, "cache").apply { mkdirs() },
        )
    val parser = SkillParser()
    return SkillManager(
        skillSourceScanner =
            SkillSourceScanner(
                bundledSkillLoader = bundledSkillLoader,
                fileSkillLoader = FileSkillLoader(parser = parser),
                skillStorage = skillStorage,
            ),
        localSkillImporter =
            LocalSkillImporter(
                contentResolver = application.contentResolver,
                skillStorage = skillStorage,
                parser = parser,
            ),
        skillRepository = skillRepository,
        toolDescriptor = toolDescriptor,
        skillConfigStore = skillConfigStore,
        skillSecretStore = skillSecretStore,
    )
}

fun testSkillSnapshot(
    id: String,
    name: String,
    sourceType: SkillSourceType = SkillSourceType.Bundled,
    workspaceSessionId: String? = null,
    commandDispatch: SkillCommandDispatch = SkillCommandDispatch.Model,
    commandTool: String? = null,
    eligibility: SkillEligibility = SkillEligibility(SkillEligibilityStatus.Eligible),
    enabled: Boolean = true,
): SkillSnapshot =
    SkillSnapshot(
        id = id,
        skillKey = name,
        sourceType = sourceType,
        workspaceSessionId = workspaceSessionId,
        baseDir = "asset://skills/$id",
        enabled = enabled,
        frontmatter =
            SkillFrontmatter(
                name = name,
                description = "Description for $name",
                homepage = null,
                userInvocable = true,
                disableModelInvocation = false,
                commandDispatch = commandDispatch,
                commandTool = commandTool,
                commandArgMode = "raw",
                metadata = null,
                unknownFields = emptyMap(),
            ),
        instructionsMd = "Do work",
        eligibility = eligibility,
    )
