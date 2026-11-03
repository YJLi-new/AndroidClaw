package ai.androidclaw.runtime.media

import ai.androidclaw.data.IMAGE_GENERATION_DEFAULT_MODEL_ID
import ai.androidclaw.data.OPENAI_DEFAULT_BASE_URL
import ai.androidclaw.data.attachments.PixelSize
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Paid calls on the live OpenAI Images API: one generation and one edit. Skipped unless
 * ANDROIDCLAW_LIVE_OPENAI_KEY is set; ANDROIDCLAW_LIVE_IMAGE_MODEL overrides the model.
 * ANDROIDCLAW_LIVE_IMAGE_OUT=<dir> (opt-in) saves `generate.png` / `edit.png` there; unset,
 * nothing is written. Each case prints one LIVE| line (latency, bytes, size); never the key.
 */
class ImageGenerateLiveTest {
    private val apiKey: String? = System.getenv("ANDROIDCLAW_LIVE_OPENAI_KEY")?.trim()?.takeIf { it.isNotEmpty() }
    private val model: String =
        System.getenv("ANDROIDCLAW_LIVE_IMAGE_MODEL")?.trim()?.takeIf { it.isNotEmpty() } ?: IMAGE_GENERATION_DEFAULT_MODEL_ID
    private val outDir: File? =
        System
            .getenv("ANDROIDCLAW_LIVE_IMAGE_OUT")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let(::File)

    @Test
    fun `generation returns a decodable png`() =
        runBlocking {
            val credentials = liveCredentials()
            val started = System.nanoTime()

            val outcome =
                OpenAiMediaClient().generateImage(
                    credentials = credentials,
                    model = model,
                    prompt = "A small blue square on a white background, flat illustration",
                    size = "1024x1024",
                    quality = "low",
                )

            assertDecodablePng("image_generate", "generate.png", outcome, started)
        }

    /** Independent of the generation case: edits a PNG drawn locally (no earlier call needed). */
    @Test
    fun `edit returns a decodable png`() =
        runBlocking {
            val credentials = liveCredentials()
            val input = ImageInput(fileName = "input.png", mimeType = "image/png", bytes = testImageBytes(256, 256))
            val started = System.nanoTime()

            val outcome =
                OpenAiMediaClient().editImage(
                    credentials = credentials,
                    model = model,
                    prompt = "Add a small yellow circle in the centre",
                    size = "1024x1024",
                    quality = "low",
                    images = listOf(input),
                )

            assertDecodablePng("image_edit", "edit.png", outcome, started)
        }

    private fun liveCredentials(): OpenAiMediaCredentials {
        assumeTrue("ANDROIDCLAW_LIVE_OPENAI_KEY not set; live image test skipped.", apiKey != null)
        return OpenAiMediaCredentials(apiKey = requireNotNull(apiKey), baseUrl = OPENAI_DEFAULT_BASE_URL)
    }

    private fun assertDecodablePng(
        name: String,
        fileName: String,
        outcome: ImageOutcome,
        startedNanos: Long,
    ) {
        val latencyMs = (System.nanoTime() - startedNanos) / 1_000_000
        val success = outcome as? ImageOutcome.Success
        val failure = outcome as? ImageOutcome.Failure
        val size = success?.bytes?.let(::decodePngOrNull)
        val saved =
            success?.let { image ->
                outDir?.let { dir -> File(dir.apply { mkdirs() }, fileName).apply { writeBytes(image.bytes) } }
            }
        println(
            "LIVE|$name model=$model latencyMs=$latencyMs " +
                "outcome=${failure?.let { "${it.httpStatus} ${it.errorCode}" } ?: "200 ok"} " +
                "bytes=${success?.bytes?.size} px=${size?.let { "${it.width}x${it.height}" }} saved=${saved?.name}",
        )
        assertNotNull("$name failed: $failure", success)
        val bytes = requireNotNull(success).bytes
        assertTrue(ImageHeaders.mimeTypeOf(bytes) == "image/png")
        assertEquals(PixelSize(1024, 1024), size)
    }
}
