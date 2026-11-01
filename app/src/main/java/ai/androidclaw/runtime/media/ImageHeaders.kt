package ai.androidclaw.runtime.media

import ai.androidclaw.data.attachments.PixelSize

/** Reads pixel size and type from PNG, JPEG and WebP headers without decoding the image. */
internal object ImageHeaders {
    fun mimeTypeOf(bytes: ByteArray): String? =
        when {
            isPng(bytes) -> "image/png"
            isJpeg(bytes) -> "image/jpeg"
            isWebp(bytes) -> "image/webp"
            else -> null
        }

    fun sizeOf(bytes: ByteArray): PixelSize? =
        when {
            isPng(bytes) -> pngSize(bytes)
            isJpeg(bytes) -> jpegSize(bytes)
            isWebp(bytes) -> webpSize(bytes)
            else -> null
        }?.takeIf { it.width > 0 && it.height > 0 }

    fun extensionFor(mimeType: String): String =
        when (mimeType) {
            "image/jpeg" -> "jpg"
            "image/webp" -> "webp"
            else -> "png"
        }

    private fun isPng(b: ByteArray): Boolean =
        b.size >= 24 &&
            b[0] == 0x89.toByte() &&
            b[1] == 'P'.code.toByte() &&
            b[2] == 'N'.code.toByte() &&
            b[3] == 'G'.code.toByte()

    private fun isJpeg(b: ByteArray): Boolean = b.size >= 4 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte()

    private fun isWebp(b: ByteArray): Boolean = b.size >= 30 && b.ascii(0, 4) == "RIFF" && b.ascii(8, 4) == "WEBP"

    private fun pngSize(b: ByteArray): PixelSize = PixelSize(b.int32(16), b.int32(20))

    private fun jpegSize(b: ByteArray): PixelSize? {
        var offset = 2
        while (offset + 9 < b.size) {
            if (b[offset] != 0xFF.toByte()) return null
            val marker = b[offset + 1].toInt() and 0xFF
            if (marker == 0xD8 || marker == 0x01 || marker in 0xD0..0xD7) {
                offset += 2
                continue
            }
            val length = b.uint16(offset + 2)
            val isStartOfFrame = marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC
            if (isStartOfFrame) {
                return PixelSize(width = b.uint16(offset + 7), height = b.uint16(offset + 5))
            }
            if (length < 2) return null
            offset += 2 + length
        }
        return null
    }

    private fun webpSize(b: ByteArray): PixelSize? =
        when (b.ascii(12, 4)) {
            "VP8X" -> PixelSize(1 + b.uint24le(24), 1 + b.uint24le(27))
            "VP8L" -> {
                val bits =
                    (b[21].toInt() and 0xFF) or ((b[22].toInt() and 0xFF) shl 8) or
                        ((b[23].toInt() and 0xFF) shl 16) or ((b[24].toInt() and 0xFF) shl 24)
                PixelSize(1 + (bits and 0x3FFF), 1 + ((bits ushr 14) and 0x3FFF))
            }
            "VP8 " -> PixelSize(b.uint16le(26) and 0x3FFF, b.uint16le(28) and 0x3FFF)
            else -> null
        }

    private fun ByteArray.ascii(
        start: Int,
        length: Int,
    ): String = String(this, start, length, Charsets.US_ASCII)

    private fun ByteArray.int32(at: Int): Int =
        ((this[at].toInt() and 0xFF) shl 24) or ((this[at + 1].toInt() and 0xFF) shl 16) or
            ((this[at + 2].toInt() and 0xFF) shl 8) or (this[at + 3].toInt() and 0xFF)

    private fun ByteArray.uint16(at: Int): Int = ((this[at].toInt() and 0xFF) shl 8) or (this[at + 1].toInt() and 0xFF)

    private fun ByteArray.uint16le(at: Int): Int = (this[at].toInt() and 0xFF) or ((this[at + 1].toInt() and 0xFF) shl 8)

    private fun ByteArray.uint24le(at: Int): Int = uint16le(at) or ((this[at + 2].toInt() and 0xFF) shl 16)
}
