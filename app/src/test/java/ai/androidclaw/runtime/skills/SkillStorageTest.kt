package ai.androidclaw.runtime.skills

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SkillStorageTest {
    private lateinit var filesDir: File
    private lateinit var cacheDir: File
    private lateinit var storage: SkillStorage

    @Before
    fun setUp() {
        filesDir = Files.createTempDirectory("androidclaw-skill-storage-files").toFile()
        cacheDir = Files.createTempDirectory("androidclaw-skill-storage-cache").toFile()
        storage = SkillStorage(filesDir = filesDir, cacheDir = cacheDir)
    }

    @After
    fun tearDown() {
        filesDir.deleteRecursively()
        cacheDir.deleteRecursively()
    }

    @Test
    fun `workspace skill directory sanitizes session id path segments`() {
        val unsafeSessionId = "../../escaped_session"

        val workspaceSkillsDir = storage.workspaceSkillsDir(unsafeSessionId)
        val workspaceRoot = File(filesDir, "workspaces").canonicalFile
        val workspaceSessionDir = workspaceSkillsDir.parentFile!!.canonicalFile

        assertEquals(unsafeSessionId.toSkillStorageSegment(fallbackPrefix = "session"), workspaceSessionDir.name)
        assertEquals("skills", workspaceSkillsDir.name)
        assertTrue(workspaceSkillsDir.canonicalPath.startsWith("${workspaceRoot.path}${File.separator}"))
    }

    @Test
    fun `storage segment sanitizer keeps safe ids and bounds unsafe ids`() {
        val safeSegment = "session_123-abc"
        val unsafeSegment = "../" + "x".repeat(SKILL_STORAGE_SEGMENT_MAX_CHARS + 50)

        val sanitized = unsafeSegment.toSkillStorageSegment(fallbackPrefix = "session")

        assertEquals(safeSegment, safeSegment.toSkillStorageSegment(fallbackPrefix = "session"))
        assertTrue(sanitized.length <= SKILL_STORAGE_SEGMENT_MAX_CHARS)
        assertTrue(sanitized.first().isLetterOrDigit())
        assertTrue(sanitized.none { char -> char == '/' || char == '\\' })
    }
}
