package ai.androidclaw.data.attachments

import kotlin.math.max
import kotlin.math.roundToInt

const val IMPORT_MAX_LONG_SIDE_PX = 1568
const val IMPORT_THUMBNAIL_LONG_SIDE_PX = 256
const val IMPORT_JPEG_QUALITY = 85
const val IMPORT_THUMBNAIL_JPEG_QUALITY = 80
const val IMPORT_MAX_BYTES = 1_500_000
const val MAX_ATTACHMENTS_PER_MESSAGE = 4

/** Width and height in pixels. */
data class PixelSize(
    val width: Int,
    val height: Int,
) {
    val longSide: Int
        get() = max(width, height)
}

/** Pure sizing rules for the import pipeline, kept free of Android types so JVM tests cover them. */
object ImageImportMath {
    /**
     * Largest power-of-two `inSampleSize` that still decodes at least [maxLongSide] pixels on the
     * long side. A 4000×3000 photo decodes at 2000×1500 (12 MB ARGB) instead of 48 MB.
     */
    fun sampleSizeFor(
        source: PixelSize,
        maxLongSide: Int = IMPORT_MAX_LONG_SIDE_PX,
    ): Int {
        require(source.width > 0 && source.height > 0) { "Image has no pixels." }
        var sample = 1
        while (source.longSide / (sample * 2) >= maxLongSide) {
            sample *= 2
        }
        return sample
    }

    fun decodedSize(
        source: PixelSize,
        sampleSize: Int,
    ): PixelSize =
        PixelSize(
            width = ceilDiv(source.width, sampleSize),
            height = ceilDiv(source.height, sampleSize),
        )

    /** Scales [size] down so its long side is at most [maxLongSide]; never scales up. */
    fun fitWithin(
        size: PixelSize,
        maxLongSide: Int,
    ): PixelSize {
        if (size.longSide <= maxLongSide) {
            return size
        }
        val scale = maxLongSide.toDouble() / size.longSide
        return PixelSize(
            width = (size.width * scale).roundToInt().coerceAtLeast(1),
            height = (size.height * scale).roundToInt().coerceAtLeast(1),
        )
    }

    /** EXIF orientations 5–8 swap width and height. */
    fun orientedSize(
        size: PixelSize,
        exifOrientation: Int,
    ): PixelSize = if (exifOrientation in 5..8) PixelSize(size.height, size.width) else size

    /** JPEG qualities tried in order until the file fits [IMPORT_MAX_BYTES]. */
    val qualityLadder: List<Int> = listOf(IMPORT_JPEG_QUALITY, 75, 65, 55)

    private fun ceilDiv(
        value: Int,
        divisor: Int,
    ): Int = (value + divisor - 1) / divisor
}
