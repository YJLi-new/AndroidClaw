package ai.androidclaw.runtime.media

import ai.androidclaw.app.AndroidClawApplication
import ai.androidclaw.data.model.AttachmentKind
import ai.androidclaw.data.model.MessageRole
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device check for the `VIDEO` attachment path without a paid call: a 2 s 320×240 H.264 fixture
 * goes through [GeneratedMediaStore.describeVideo] (the real `MediaMetadataRetriever` poster
 * frame) and is attached to an assistant message in the main session, the way `VideoJobWorker`
 * does after a download. The message is removed afterwards unless the runner argument
 * `keepVideoFixture=true` is passed, which leaves it in place for the manual device journey.
 */
@RunWith(AndroidJUnit4::class)
class VideoAttachmentDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val application = instrumentation.targetContext.applicationContext as AndroidClawApplication
    private val container = application.container

    @Test
    fun fixtureVideoBecomesVideoAttachmentWithPoster() =
        runBlocking {
            val session = container.sessionRepository.getOrCreateMainSession()
            val (attachmentId, file) = container.generatedMediaStore.newVideoTarget(session.id)
            file.parentFile?.mkdirs()
            instrumentation.context.assets.open("fixtures/sample_video.mp4").use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }

            val (staged, durationMs) = container.generatedMediaStore.describeVideo(session.id, attachmentId, file)
            val message =
                container.messageRepository.addMessage(
                    sessionId = session.id,
                    role = MessageRole.Assistant,
                    content = "Video ready (2 s, 320×240): fixture clip",
                )
            val attached =
                container.attachmentRepository.attachToMessage(
                    messageId = message.id,
                    sessionId = session.id,
                    staged = listOf(staged),
                    kind = AttachmentKind.Video,
                    durationMs = durationMs,
                )

            assertEquals(320, staged.width)
            assertEquals(240, staged.height)
            assertNotNull(durationMs)
            assertTrue("duration $durationMs", durationMs!! in 1_500L..2_500L)
            val poster = container.attachmentRepository.store.thumbnailFile(staged.relativePath)
            assertTrue("poster frame written", poster.isFile)
            assertNotNull(BitmapFactory.decodeFile(poster.path))
            assertEquals(AttachmentKind.Video, attached.single().kind)

            val keep = InstrumentationRegistry.getArguments().getString("keepVideoFixture") == "true"
            if (!keep) {
                container.messageRepository.deleteMessage(message.id)
            }
        }
}
