package ai.androidclaw.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WorkspaceStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun store(): WorkspaceStore = WorkspaceStore(temporaryFolder.newFolder("workspace"))

    @Test
    fun `defaults are created once and survive user edits`() =
        runTest {
            val store = store()
            assertEquals(WorkspaceStore.PERSONA_FILE_NAMES, store.personaFiles().map { it.path })

            store.write("SOUL.md", "# SOUL.md\nBe terse.")
            store.ensureDefaults()

            assertEquals("# SOUL.md\nBe terse.", store.read("SOUL.md"))
            assertTrue(store.reset("SOUL.md"))
            assertEquals(WorkspaceStore.DEFAULT_TEMPLATES["SOUL.md"], store.read("SOUL.md"))
            assertFalse(store.reset("notes.md"))
        }

    @Test
    fun `paths are confined to the workspace`() =
        runTest {
            val store = store()

            listOf("../escape.md", "a/../../b.md", "", "  ", "bad|name.md").forEach { path ->
                assertThrows(WorkspaceException::class.java) { store.resolve(path) }
            }
            assertEquals(store.rootDir.canonicalFile.resolve("notes/a.md"), store.resolve("/notes/a.md"))
            assertNull(store.read("missing.md"))
        }

    @Test
    fun `file size is bounded`() =
        runTest {
            val store = store()
            val tooLarge = "x".repeat(WorkspaceStore.MAX_FILE_BYTES + 1)

            val error = runCatching { store.write("big.md", tooLarge) }.exceptionOrNull()

            assertTrue(error is WorkspaceException)
            assertNull(store.read("big.md"))
        }

    @Test
    fun `prompt context follows persona order and marks truncation`() =
        runTest {
            val store = store()
            store.ensureDefaults()
            store.write("MEMORY.md", "# MEMORY.md\n" + "fact ".repeat(4_000))

            val context = store.promptContext(maxBytes = 2_048)!!

            assertTrue(context.indexOf("## AGENTS.md") < context.indexOf("## SOUL.md"))
            assertTrue(context.indexOf("## USER.md") < context.indexOf("## MEMORY.md"))
            assertTrue(context.contains("[truncated: MEMORY.md is longer; read it with workspace_read]"))
            assertTrue(context.toByteArray(Charsets.UTF_8).size <= 2_048)
        }

    @Test
    fun `list shows persona files first`() =
        runTest {
            val store = store()
            store.write("a-notes/today.md", "hi")

            val paths = store.list().map { it.path }

            assertEquals(WorkspaceStore.PERSONA_FILE_NAMES.sorted(), paths.take(4).sorted())
            assertEquals("a-notes/today.md", paths.last())
        }
}
