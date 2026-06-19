package ai.androidclaw.runtime.skills

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class LocalSkillImporterTest {
    private lateinit var filesDir: File
    private lateinit var cacheDir: File
    private lateinit var storage: SkillStorage
    private lateinit var importer: LocalSkillImporter

    @Before
    fun setUp() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        filesDir = Files.createTempDirectory("androidclaw-skill-files").toFile()
        cacheDir = Files.createTempDirectory("androidclaw-skill-cache").toFile()
        storage = SkillStorage(filesDir = filesDir, cacheDir = cacheDir)
        importer =
            LocalSkillImporter(
                contentResolver = application.contentResolver,
                skillStorage = storage,
                parser = SkillParser(),
            )
    }

    @After
    fun tearDown() {
        filesDir.deleteRecursively()
        cacheDir.deleteRecursively()
    }

    @Test
    fun `import zip stores unsafe skill names inside local skill root`() =
        runTest {
            val unsafeName = "../../escaped_skill"

            val result =
                importer.importZipStream(
                    inputStream = ByteArrayInputStream(skillArchiveBytes(skillName = unsafeName)),
                    sourceName = "unsafe.zip",
                )

            val localRoot = storage.localSkillsDir.canonicalFile
            val installedDirectories = localRoot.listFiles().orEmpty().filter(File::isDirectory)
            val installedDirectory = installedDirectories.single().canonicalFile

            assertEquals(listOf(unsafeName), result.importedSkillNames)
            assertEquals(unsafeName.toSkillStorageSegment(fallbackPrefix = "skill"), installedDirectory.name)
            assertTrue(installedDirectory.path.startsWith("${localRoot.path}${File.separator}"))
            assertTrue(File(installedDirectory, "SKILL.md").isFile)
            assertFalse(File(filesDir, "escaped_skill").exists())
        }

    @Test
    fun `import zip preserves exact directory names for safe skills and replaces on repeat import`() =
        runTest {
            val firstImport =
                importer.importZipStream(
                    inputStream = ByteArrayInputStream(skillArchiveBytes(skillName = "safe_skill", body = "First body.")),
                    sourceName = "safe.zip",
                )
            val secondImport =
                importer.importZipStream(
                    inputStream = ByteArrayInputStream(skillArchiveBytes(skillName = "safe_skill", body = "Second body.")),
                    sourceName = "safe.zip",
                )

            val installedDirectories =
                storage.localSkillsDir
                    .listFiles()
                    .orEmpty()
                    .filter(File::isDirectory)
            val installedSkill = File(storage.localSkillsDir, "safe_skill/SKILL.md")

            assertEquals(listOf("safe_skill"), firstImport.importedSkillNames)
            assertEquals(emptyList<String>(), firstImport.replacedSkillNames)
            assertEquals(listOf("safe_skill"), secondImport.importedSkillNames)
            assertEquals(listOf("safe_skill"), secondImport.replacedSkillNames)
            assertEquals(listOf("safe_skill"), installedDirectories.map(File::getName))
            assertTrue(installedSkill.readText().contains("Second body."))
        }

    @Test
    fun `import skill documents writes generated skill markdown and replaces by skill key`() =
        runTest {
            val entry =
                SkillPackageImportEntry(
                    sourceIndex = 0,
                    frontmatter =
                        SkillFrontmatter(
                            name = "document_skill",
                            description = "Imported from skills.export",
                            homepage = null,
                            userInvocable = true,
                            disableModelInvocation = false,
                            commandDispatch = SkillCommandDispatch.Tool,
                            commandTool = "tools.echo",
                            commandArgMode = "raw",
                            metadata = null,
                            unknownFields = emptyMap(),
                        ),
                    instructionsMd = "Document body.",
                    sourceEnabled = true,
                )

            val firstImport = importer.importSkillDocuments(listOf(entry))
            val secondImport = importer.importSkillDocuments(listOf(entry.copy(instructionsMd = "Replacement body.")))

            val installedSkill = File(storage.localSkillsDir, "document_skill/SKILL.md")

            assertEquals(listOf("document_skill"), firstImport.importedSkillNames)
            assertEquals(emptyList<String>(), firstImport.replacedSkillNames)
            assertEquals(listOf("document_skill"), secondImport.importedSkillNames)
            assertEquals(listOf("document_skill"), secondImport.replacedSkillNames)
            assertTrue(installedSkill.isFile)
            val document = installedSkill.readText()
            assertTrue(document.contains("name: document_skill"))
            assertTrue(document.contains("command-tool: tools.echo"))
            assertTrue(document.contains("Replacement body."))
        }

    @Test
    fun `import zip rejects oversized archive entries before install`() =
        runTest {
            val error =
                runCatching {
                    importer.importZipStream(
                        inputStream = ByteArrayInputStream(oversizedArchiveBytes()),
                        sourceName = "oversized.zip",
                    )
                }.exceptionOrNull()

            assertEquals("Skill archive entry is too large to import.", error?.message)
            assertFalse(storage.localSkillsDir.exists())
        }
}

private fun skillArchiveBytes(
    skillName: String,
    body: String = "Do work.",
): ByteArray {
    val output = ByteArrayOutputStream()
    ZipOutputStream(output).use { zip ->
        zip.putNextEntry(ZipEntry("skill/SKILL.md"))
        zip.write(
            """
            ---
            name: "$skillName"
            description: Test skill
            ---
            
            $body
            """.trimIndent().toByteArray(),
        )
        zip.closeEntry()
    }
    return output.toByteArray()
}

private fun oversizedArchiveBytes(): ByteArray {
    val output = ByteArrayOutputStream()
    ZipOutputStream(output).use { zip ->
        zip.putNextEntry(ZipEntry("skill/SKILL.md"))
        zip.write(ByteArray(1_048_577) { 'x'.code.toByte() })
        zip.closeEntry()
    }
    return output.toByteArray()
}
