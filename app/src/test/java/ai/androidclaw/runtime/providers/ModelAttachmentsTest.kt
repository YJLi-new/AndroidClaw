package ai.androidclaw.runtime.providers

import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.Clock

class ModelAttachmentsTest {
    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("attach-bound").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun `marker names id, display name and size`() {
        assertEquals("[attachment x1: cat.jpg 1568×1176]", attachment("x1", name = "cat.jpg", w = 1568, h = 1176).marker())
    }

    @Test
    fun `only the latest two image turns keep bytes`() {
        val history =
            listOf(
                user("oldest", attachment("a")),
                user("middle", attachment("b")),
                ModelMessage(ModelMessageRole.Assistant, "ok"),
                user("newest", attachment("c")),
            )
        val bounded = history.withImageRequestBound(imagesEnabled = true, resolveFile = ::resolve)
        assertNull(bounded[0].attachments.single().file)
        assertNotNull(bounded[1].attachments.single().file)
        assertNotNull(bounded[3].attachments.single().file)
        assertTrue(bounded[0].contentWithAttachmentMarkers().endsWith("[attachment a: a.jpg 100×100]"))
    }

    @Test
    fun `at most four images per request, newest first`() {
        val history =
            listOf(
                user("one", attachment("a1"), attachment("a2"), attachment("a3")),
                user("two", attachment("b1"), attachment("b2"), attachment("b3")),
            )
        val bounded = history.withImageRequestBound(imagesEnabled = true, resolveFile = ::resolve)
        assertEquals(3, bounded[1].attachments.count { it.file != null })
        assertEquals(listOf(true, false, false), bounded[0].attachments.map { it.file != null })
    }

    @Test
    fun `encoded byte budget is 8 MB`() {
        val big = 3L * 1024 * 1024 // 4 MB as base64
        val history = listOf(user("x", attachment("a", bytes = big), attachment("b", bytes = big), attachment("c", bytes = big)))
        val bounded = history.withImageRequestBound(imagesEnabled = true, resolveFile = ::resolve)
        assertEquals(listOf(true, true, false), bounded[0].attachments.map { it.file != null })
    }

    @Test
    fun `images disabled or files missing send markers only`() {
        val history = listOf(user("x", attachment("a")))
        val disabled = history.withImageRequestBound(imagesEnabled = false, resolveFile = ::resolve)
        assertNull(disabled[0].attachments.single().file)
        val missing = history.withImageRequestBound(imagesEnabled = true, resolveFile = { File(dir, "nope.jpg") })
        assertNull(missing[0].attachments.single().file)
    }

    @Test
    fun `fake provider replies with image count and sizes`() =
        runTest {
            val file = resolve(attachment("a"))
            val request =
                ModelRequest(
                    sessionId = "s",
                    requestId = "r",
                    messageHistory =
                        listOf(
                            user("Look", attachment("a", w = 1568, h = 1176).copy(file = file), attachment("b", w = 256, h = 192).copy(file = file)),
                        ),
                    systemPrompt = "",
                    enabledSkills = emptyList(),
                    toolDescriptors = emptyList(),
                    runMode = ModelRunMode.Interactive,
                )
            val reply = FakeProvider(Clock.systemUTC()).generate(request).text
            assertEquals("Reply: Look\nImages: 2 (1568×1176, 256×192)", reply)
        }

    private fun resolve(attachment: ModelAttachment): File = File(dir, "${attachment.id}.jpg").apply { if (!exists()) writeBytes(byteArrayOf(1)) }

    private fun user(
        text: String,
        vararg attachments: ModelAttachment,
    ) = ModelMessage(ModelMessageRole.User, text, attachments = attachments.toList())

    private fun attachment(
        id: String,
        name: String = "$id.jpg",
        w: Int = 100,
        h: Int = 100,
        bytes: Long = 1_000,
    ) = ModelAttachment(id, name, "image/jpeg", w, h, bytes, "s/$id.jpg")
}
