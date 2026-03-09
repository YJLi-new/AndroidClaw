package ai.androidclaw.runtime.skills

import android.content.ContentResolver
import android.net.Uri
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class SkillImportResult(
    val importedSkillNames: List<String>,
    val replacedSkillNames: List<String>,
)

class LocalSkillImporter(
    private val contentResolver: ContentResolver,
    private val skillStorage: SkillStorage,
    private val parser: SkillParser,
) {
    suspend fun importZip(uri: Uri): SkillImportResult = withContext(Dispatchers.IO) {
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
    ): SkillImportResult = withContext(Dispatchers.IO) {
        val scratchDir = skillStorage.importScratchDir(UUID.randomUUID().toString())
        scratchDir.deleteRecursively()
        scratchDir.mkdirs()
        try {
            extractArchive(inputStream = inputStream, scratchDir = scratchDir)
            val candidates = scratchDir.walkTopDown()
                .filter { it.isFile && it.name == "SKILL.md" }
                .map { it.parentFile }
                .filterNotNull()
                .distinctBy(File::getAbsolutePath)
                .toList()
            require(candidates.isNotEmpty()) {
                "Selected archive does not contain any skill directories."
            }

            val stagedSkills = candidates.sortedBy(File::getName).map { directory ->
                val skillDocument = File(directory, "SKILL.md").readText()
                val parsed = when (val result = parser.parse(skillDocument)) {
                    is SkillParseResult.Success -> result.document
                    is SkillParseResult.Failure -> error("Invalid SKILL.md in ${directory.name}: ${result.error}")
                }
                ImportedSkill(
                    name = parsed.frontmatter.name,
                    sourceDir = directory,
                )
            }

            val localRoot = skillStorage.localSkillsDir.apply { mkdirs() }
            val replaced = mutableListOf<String>()
            val imported = mutableListOf<String>()
            stagedSkills.forEach { importedSkill ->
                val existing = File(localRoot, importedSkill.name)
                val stage = File(localRoot, ".${importedSkill.name}-${UUID.randomUUID()}")
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

    private fun extractArchive(
        inputStream: InputStream,
        scratchDir: File,
    ) {
        var totalBytes = 0L
        ZipInputStream(inputStream.buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
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
                        while (true) {
                            val read = zip.read(buffer)
                            if (read <= 0) break
                            totalBytes += read
                            check(totalBytes <= MAX_TOTAL_UNCOMPRESSED_BYTES) {
                                "Skill archive is too large to import."
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
        val sourceDir: File,
    )

    private companion object {
        const val MAX_TOTAL_UNCOMPRESSED_BYTES = 10L * 1024L * 1024L
    }
}
