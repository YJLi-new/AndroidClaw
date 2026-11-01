package ai.androidclaw.runtime.media

import ai.androidclaw.data.attachments.PixelSize
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/** The debug fake backend draws a real, deterministic PNG with Canvas. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FakeImageBackendTest {
    @Test
    fun `same prompt gives the same decodable png`() =
        runBlocking {
            val backend = FakeImageBackend()
            val first = backend.generate(ImageGenerationRequest("a red fox", "1536x1024", null)) as ImageOutcome.Success
            val second = backend.generate(ImageGenerationRequest("a red fox", "1536x1024", null)) as ImageOutcome.Success

            assertEquals("image/png", first.mimeType)
            assertEquals(PixelSize(512, 341), ImageHeaders.sizeOf(first.bytes))
            assertArrayEquals(first.bytes, second.bytes)
            assertEquals(PixelSize(512, 341), decodePngOrNull(first.bytes))
        }
}
