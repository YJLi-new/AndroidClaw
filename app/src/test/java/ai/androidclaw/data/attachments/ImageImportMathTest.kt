package ai.androidclaw.data.attachments

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageImportMathTest {
    @Test
    fun `12 MP photo decodes at half size, never at full resolution`() {
        val source = PixelSize(4000, 3000)
        val sample = ImageImportMath.sampleSizeFor(source)
        assertEquals(2, sample)
        val decoded = ImageImportMath.decodedSize(source, sample)
        assertEquals(PixelSize(2000, 1500), decoded)
        val decodedBytes = decoded.width.toLong() * decoded.height * 4
        val fullBytes = source.width.toLong() * source.height * 4
        assertTrue(decodedBytes * 4 <= fullBytes)
        assertEquals(PixelSize(1568, 1176), ImageImportMath.fitWithin(decoded, IMPORT_MAX_LONG_SIDE_PX))
    }

    @Test
    fun `sampled decode always keeps at least the target long side`() {
        for (long in listOf(1568, 2000, 3136, 3137, 4032, 6000, 8000, 12000, 20000)) {
            val source = PixelSize(long, long * 3 / 4)
            val sample = ImageImportMath.sampleSizeFor(source)
            val decoded = ImageImportMath.decodedSize(source, sample)
            assertTrue("$long -> $sample", decoded.longSide >= IMPORT_MAX_LONG_SIDE_PX)
            assertTrue("$long -> $sample", decoded.longSide < IMPORT_MAX_LONG_SIDE_PX * 2)
            if (long >= IMPORT_MAX_LONG_SIDE_PX * 2) assertTrue(sample > 1)
        }
    }

    @Test
    fun `small images are not sampled or upscaled`() {
        val small = PixelSize(800, 600)
        assertEquals(1, ImageImportMath.sampleSizeFor(small))
        assertEquals(small, ImageImportMath.fitWithin(small, IMPORT_MAX_LONG_SIDE_PX))
    }

    @Test
    fun `thumbnail fits 256 px and exif rotations swap sides`() {
        assertEquals(PixelSize(256, 192), ImageImportMath.fitWithin(PixelSize(1568, 1176), IMPORT_THUMBNAIL_LONG_SIDE_PX))
        assertEquals(PixelSize(3000, 4000), ImageImportMath.orientedSize(PixelSize(4000, 3000), 6))
        assertEquals(PixelSize(4000, 3000), ImageImportMath.orientedSize(PixelSize(4000, 3000), 3))
    }
}
