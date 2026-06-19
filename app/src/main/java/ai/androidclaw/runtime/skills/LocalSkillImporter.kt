package ai.androidclaw.runtime.skills

import android.content.ContentResolver
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.zip.ZipInputStream

data class SkillImportResult(
    val importedSkillNames: List<String>,
    val replacedSkillNames: List<String>,
)

class LocalSkillImporter(
    private val contentResolver: ContentResolver,
    private val skillStorage: SkillStorage,
    private val parser: SkillParser,
) {
    suspend fun importZip(uri: Uri): SkillImportResult =
        withContext(Dispatchers.IO) {
            contentResolver.openInputStream(uri)?.use { inputStream ->
                importZipStream(
                    inputStream = inputStream,
                    sourceName = uri.lastPathSegment ?: "skills.zip",
                )
            } ?: error("Unable to open selected skill archive.")
        }

    internal suspend fun importZipStream(
        inputStream: InputStream,
        sourceName: String,
    ): SkillImportResult =
        withContext(Dispatchers.IO) {
            val scratchDir = skillStorage.importScratchDir(UUID.randomUUID().toString())
            scratchDir.deleteRecursively()
            scratchDir.mkdirs()
            try {
                extractArchive(inputStream = inputStream, scratchDir = scratchDir)
                val candidates =
                    scratchDir
                        .walkTopDown()
                        .filter { it.isFile && it.name == "SKILL.md" }
                        .map { it.parentFile }
                        .filterNotNull()
                        .distinctBy(File::getAbsolutePath)
                        .toList()
                require(candidates.isNotEmpty()) {
                    "Selected archive does not contain any skill directories."
                }

                val stagedSkills =
                    candidates.sortedBy(File::getName).map { directory ->
                        val skillDocument = File(directory, "SKILL.md").readText()
                        val parsed =
                            when (val result = parser.parse(skillDocument)) {
                                is SkillParseResult.Success -> result.document
                                is SkillParseResult.Failure -> error("Invalid SKILL.md in ${directory.name}: ${result.error}")
                            }
                        ImportedSkill(
                            name = parsed.frontmatter.name,
                            installDirName = parsed.frontmatter.skillKey().toSkillStorageSegment(fallbackPrefix = "skill"),
                            sourceDir = directory,
                        )
                    }

                val localRoot = skillStorage.localSkillsDir.apply { mkdirs() }
                val replaced = mutableListOf<String>()
                val imported = mutableListOf<String>()
                stagedSkills.forEach { importedSkill ->
                    val existing = File(localRoot, importedSkill.installDirName).requireChildOf(localRoot)
                    val stage = File(localRoot, ".${importedSkill.installDirName}-${UUID.randomUUID()}").requireChildOf(localRoot)
                    stage.deleteRecursively()
                    importedSkill.sourceDir.copyRecursively(stage, overwrite = true)
                    if (existing.exists()) {
                        replaced += importedSkill.name
                        existing.deleteRecursively()
                    }
                    check(stage.renameTo(existing)) {
                        "Unable to install skill ${importedSkill.name}."
                    }
                    imported += importedSkill.name
                }

                SkillImportResult(
                    importedSkillNames = imported,
                    replacedSkillNames = replaced.distinct(),
                )
            } finally {
                scratchDir.deleteRecursively()
            }
        }

    internal suspend fun importSkillDocuments(entries: List<SkillPackageImportEntry>): SkillImportResult =
        withContext(Dispatchers.IO) {
            val localRoot = skillStorage.localSkillsDir.apply { mkdirs() }
            val replaced = mutableListOf<String>()
            val imported = mutableListOf<String>()
            entries.forEach { entry ->
                val rawDocument = entry.toSkillMarkdownDocument()
                when (val parsed = parser.parse(rawDocument)) {
                    is SkillParseResult.Success -> {
                        val installDirName =
                            parsed.document.frontmatter
                                .skillKey()
                                .toSkillStorageSegment(fallbackPrefix = "skill")
                        val existing = File(localRoot, installDirName).requireChildOf(localRoot)
                        val stage = File(localRoot, ".$installDirName-${UUID.randomUUID()}").requireChildOf(localRoot)
                        stage.deleteRecursively()
                        stage.mkdirs()
                        File(stage, "SKILL.md").writeText(rawDocument)
                        if (existing.exists()) {
                            replaced += parsed.document.frontmatter.name
                            existing.deleteRecursively()
                        }
                        check(stage.renameTo(existing)) {
                            "Unable to install skill ${parsed.document.frontmatter.name}."
                        }
                        imported += parsed.document.frontmatter.name
                    }
                    is SkillParseResult.Failure -> error("Invalid imported SKILL.md for ${entry.frontmatter.name}: ${parsed.error}")
                }
            }

            SkillImportResult(
                importedSkillNames = imported,
                replacedSkillNames = replaced.distinct(),
            )
        }

    private fun extractArchive(
        inputStream: InputStream,
        scratchDir: File,
    ) {
        var totalBytes = 0L
        var entryCount = 0
        ZipInputStream(inputStream.buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                entryCount += 1
                check(entryCount <= MAX_ARCHIVE_ENTRIES) {
                    "Skill archive contains too many entries."
                }
                val normalizedName = entry.name.replace('\\', '/').trimStart('/')
                if (normalizedName.isBlank()) {
                    zip.closeEntry()
                    entry = zip.nextEntry
                    continue
                }
                val destination = File(scratchDir, normalizedName)
                val canonicalScratch = scratchDir.canonicalFile
                val canonicalDestination = destination.canonicalFile
                check(
                    canonicalDestination.path == canonicalScratch.path ||
                        canonicalDestination.path.startsWith("${canonicalScratch.path}${File.separator}"),
                ) {
                    "Archive entry escapes the skill import root: $normalizedName"
                }

                if (entry.isDirectory) {
                    destination.mkdirs()
                } else {
                    destination.parentFile?.mkdirs()
                    destination.outputStream().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var entryBytes = 0L
                        while (true) {
                            val read = zip.read(buffer)
                            if (read <= 0) break
                            totalBytes += read
                            entryBytes += read
                            check(totalBytes <= MAX_TOTAL_UNCOMPRESSED_BYTES) {
                                "Skill archive is too large to import."
                            }
                            check(entryBytes <= MAX_ENTRY_UNCOMPRESSED_BYTES) {
                                "Skill archive entry is too large to import."
                            }
                            output.write(buffer, 0, read)
                        }
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
    }

    private data class ImportedSkill(
        val name: String,
        val installDirName: String,
        val sourceDir: File,
    )

    private companion object {
        const val MAX_ARCHIVE_ENTRIES = 256
        const val MAX_ENTRY_UNCOMPRESSED_BYTES = 1L * 1024L * 1024L
        const val MAX_TOTAL_UNCOMPRESSED_BYTES = 10L * 1024L * 1024L
    }
}

private fun SkillPackageImportEntry.toSkillMarkdownDocument(): String {
    val frontmatterYaml =
        skillYaml.dump(frontmatter.toSkillYamlMap()).trimEnd()
    return buildString {
        appendLine("---")
        appendLine(frontmatterYaml)
        appendLine("---")
        appendLine()
        append(instructionsMd.trim())
        if (!endsWith("\n")) {
            appendLine()
        }
    }
}

private val skillYaml = Yaml()

private fun SkillFrontmatter.toSkillYamlMap(): Map<String, Any?> =
    linkedMapOf<String, Any?>().apply {
        put("name", name)
        put("description", description)
        homepage?.let { put("homepage", it) }
        put("user-invocable", userInvocable)
        put("disable-model-invocation", disableModelInvocation)
        put(
            "command-dispatch",
            when (commandDispatch) {
                SkillCommandDispatch.Model -> "model"
                SkillCommandDispatch.Tool -> "tool"
            },
        )
        commandTool?.let { put("command-tool", it) }
        put("command-arg-mode", commandArgMode)
        metadata?.takeUnless { it is JsonNull }?.let { put("metadata", it.toSkillYamlValue()) }
        unknownFields.forEach { (field, value) ->
            if (field !in knownSkillYamlFields) {
                put(field, value.toSkillYamlValue())
            }
        }
    }

private val knownSkillYamlFields =
    setOf(
        "name",
        "description",
        "homepage",
        "user-invocable",
        "disable-model-invocation",
        "command-dispatch",
        "command-tool",
        "command-arg-mode",
        "metadata",
    )

private fun JsonElement.toSkillYamlValue(): Any? =
    when (this) {
        JsonNull -> null
        is JsonObject ->
            entries.associate { (key, value) ->
                key to value.toSkillYamlValue()
            }
        is JsonArray -> map { value -> value.toSkillYamlValue() }
        is JsonPrimitive ->
            when {
                isString -> content
                booleanOrNull != null -> booleanOrNull
                longOrNull != null -> longOrNull
                doubleOrNull != null -> doubleOrNull
                else -> contentOrNull
            }
    }

private fun File.requireChildOf(root: File): File {
    val canonicalRoot = root.canonicalFile
    val canonicalFile = canonicalFile
    check(
        canonicalFile.path == canonicalRoot.path ||
            canonicalFile.path.startsWith("${canonicalRoot.path}${File.separator}"),
    ) {
        "Skill import path escapes the local skill root: $name"
    }
    return this
}
