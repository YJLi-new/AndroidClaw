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

/**
 * One paid image generation on the live OpenAI Images API. Skipped unless
 * ANDROIDCLAW_LIVE_OPENAI_KEY is set; ANDROIDCLAW_LIVE_IMAGE_MODEL overrides the model.
 * Prints one LIVE| line (latency, bytes, size); never prints the key.
 */
class ImageGenerateLiveTest {
    private val apiKey: String? = System.getenv("ANDROIDCLAW_LIVE_OPENAI_KEY")?.trim()?.takeIf { it.isNotEmpty() }
    private val model: String =
        System.getenv("ANDROIDCLAW_LIVE_IMAGE_MODEL")?.trim()?.takeIf { it.isNotEmpty() } ?: IMAGE_GENERATION_DEFAULT_MODEL_ID

    @Test
    fun `generation returns a decodable png`() =
        runBlocking {
            assumeTrue("ANDROIDCLAW_LIVE_OPENAI_KEY not set; live image test skipped.", apiKey != null)
            val credentials = OpenAiMediaCredentials(apiKey = requireNotNull(apiKey), baseUrl = OPENAI_DEFAULT_BASE_URL)
            val started = System.nanoTime()

            val outcome =
                OpenAiMediaClient().generateImage(
                    credentials = credentials,
                    model = model,
                    prompt = "A small blue square on a white background, flat illustration",
                    size = "1024x1024",
                    quality = "low",
                )

            val latencyMs = (System.nanoTime() - started) / 1_000_000
            val success = outcome as? ImageOutcome.Success
            println("LIVE|image_generate model=$model latencyMs=$latencyMs outcome=${(outcome as? ImageOutcome.Failure)?.let { "${it.httpStatus} ${it.errorCode}" } ?: "ok"} bytes=${success?.bytes?.size}")
            assertNotNull("generation failed: $outcome", success)
            val bytes = requireNotNull(success).bytes
            assertTrue(ImageHeaders.mimeTypeOf(bytes) == "image/png")
            assertEquals(PixelSize(1024, 1024), decodePngOrNull(bytes))
        }
}
