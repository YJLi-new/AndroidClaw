package ai.androidclaw.runtime.media

import ai.androidclaw.data.ProviderType
import ai.androidclaw.data.attachments.PixelSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaRulesTest {
    @Test
    fun `backend rule follows the fake provider, then the OpenAI key`() {
        assertEquals(MediaBackendMode.Fake, resolveMediaBackendMode(ProviderType.Fake, hasOpenAiKey = false, fakeBackendAllowed = true))
        assertEquals(MediaBackendMode.Fake, resolveMediaBackendMode(ProviderType.Fake, hasOpenAiKey = true, fakeBackendAllowed = true))
        assertEquals(MediaBackendMode.Off, resolveMediaBackendMode(ProviderType.Fake, hasOpenAiKey = false, fakeBackendAllowed = false))
        assertEquals(MediaBackendMode.OpenAi, resolveMediaBackendMode(ProviderType.Fake, hasOpenAiKey = true, fakeBackendAllowed = false))
        // Any chat provider works once the OpenAI key is saved.
        assertEquals(MediaBackendMode.OpenAi, resolveMediaBackendMode(ProviderType.Anthropic, hasOpenAiKey = true, fakeBackendAllowed = true))
        assertEquals(MediaBackendMode.OpenAi, resolveMediaBackendMode(ProviderType.DeepSeek, hasOpenAiKey = true, fakeBackendAllowed = false))
        assertEquals(MediaBackendMode.Off, resolveMediaBackendMode(ProviderType.OpenAi, hasOpenAiKey = false, fakeBackendAllowed = true))
    }

    @Test
    fun `tools are offered per mode and video has no fake backend`() {
        assertTrue(MediaBackendMode.OpenAi.offersTool(IMAGE_GENERATE_TOOL_NAME))
        assertTrue(MediaBackendMode.OpenAi.offersTool(VIDEO_GENERATE_TOOL_NAME))
        assertTrue(MediaBackendMode.Fake.offersTool(IMAGE_GENERATE_TOOL_NAME))
        assertFalse(MediaBackendMode.Fake.offersTool(VIDEO_GENERATE_TOOL_NAME))
        assertFalse(MediaBackendMode.Off.offersTool(IMAGE_GENERATE_TOOL_NAME))
        assertFalse(MediaBackendMode.Off.offersTool(VIDEO_GENERATE_TOOL_NAME))
        assertTrue(MediaBackendMode.Off.offersTool("web_fetch"))
    }

    @Test
    fun `headers give png and jpeg sizes`() {
        assertEquals(PixelSize(37, 21), ImageHeaders.sizeOf(testImageBytes(37, 21)))
        assertEquals("image/png", ImageHeaders.mimeTypeOf(testImageBytes(2, 2)))
        val jpeg = testImageBytes(50, 13, "jpg")
        assertEquals(PixelSize(50, 13), ImageHeaders.sizeOf(jpeg))
        assertEquals("image/jpeg", ImageHeaders.mimeTypeOf(jpeg))
        assertNull(ImageHeaders.sizeOf("not an image at all, just text".toByteArray()))
    }

    @Test
    fun `headers give webp sizes`() {
        val vp8x = webp("VP8X", ByteArray(4) + le24(1023) + le24(767))
        assertEquals(PixelSize(1024, 768), ImageHeaders.sizeOf(vp8x))
        assertEquals("image/webp", ImageHeaders.mimeTypeOf(vp8x))
        val bits = (100 - 1) or ((60 - 1) shl 14)
        val vp8l = webp("VP8L", byteArrayOf(0x2f) + le24(bits and 0xFFFFFF) + byteArrayOf((bits ushr 24).toByte()))
        assertEquals(PixelSize(100, 60), ImageHeaders.sizeOf(vp8l))
    }

    @Test
    fun `fake image keeps the aspect ratio at 512 px`() {
        assertEquals(512 to 512, fakeImageSize(null))
        assertEquals(512 to 512, fakeImageSize("auto"))
        assertEquals(512 to 341, fakeImageSize("1536x1024"))
        assertEquals(341 to 512, fakeImageSize("1024x1536"))
    }

    private fun webp(
        chunk: String,
        payload: ByteArray,
    ): ByteArray {
        val body = "WEBP".toByteArray() + chunk.toByteArray() + le32(payload.size) + payload
        return ("RIFF".toByteArray() + le32(body.size) + body).copyOf(maxOf(30, 8 + body.size))
    }

    private fun le24(value: Int): ByteArray = byteArrayOf(value.toByte(), (value shr 8).toByte(), (value shr 16).toByte())

    private fun le32(value: Int): ByteArray = le24(value) + byteArrayOf((value shr 24).toByte())
}
