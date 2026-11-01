package ai.androidclaw.runtime.media

import ai.androidclaw.data.attachments.PixelSize
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.Base64
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * Test images without desktop image libraries (unit tests compile against android.jar):
 * a real RGB PNG, or a JPEG with a valid SOF0 header (not decodable, enough for headers).
 */
internal fun testImageBytes(
    width: Int,
    height: Int,
    format: String = "png",
): ByteArray = if (format == "png") testPng(width, height) else testJpegHeader(width, height)

private fun testPng(
    width: Int,
    height: Int,
): ByteArray {
    val raw = ByteArrayOutputStream()
    for (y in 0 until height) {
        raw.write(0)
        for (x in 0 until width) {
            raw.write(x * 3)
            raw.write(y * 5)
            raw.write(128)
        }
    }
    val deflater = Deflater()
    deflater.setInput(raw.toByteArray())
    deflater.finish()
    val compressed = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (!deflater.finished()) compressed.write(buffer, 0, deflater.deflate(buffer))
    val ihdr =
        ByteBuffer
            .allocate(13)
            .putInt(width)
            .putInt(height)
            .put(8)
            .put(2)
            .put(0)
            .put(0)
            .put(0)
            .array()
    return ByteArrayOutputStream()
        .apply {
            write(PNG_SIGNATURE)
            writeChunk("IHDR", ihdr)
            writeChunk("IDAT", compressed.toByteArray())
            writeChunk("IEND", ByteArray(0))
        }.toByteArray()
}

private fun ByteArrayOutputStream.writeChunk(
    type: String,
    data: ByteArray,
) {
    write(ByteBuffer.allocate(4).putInt(data.size).array())
    val typeBytes = type.toByteArray(Charsets.US_ASCII)
    write(typeBytes)
    write(data)
    val crc = CRC32().apply { update(typeBytes) }.apply { update(data) }
    write(ByteBuffer.allocate(4).putInt(crc.value.toInt()).array())
}

private fun testJpegHeader(
    width: Int,
    height: Int,
): ByteArray =
    byteArrayOf(0xFF.toByte(), 0xD8.toByte()) +
        byteArrayOf(0xFF.toByte(), 0xE0.toByte(), 0, 16) + "JFIF".toByteArray() + ByteArray(10) +
        byteArrayOf(0xFF.toByte(), 0xC0.toByte(), 0, 17, 8) +
        byteArrayOf((height shr 8).toByte(), height.toByte(), (width shr 8).toByte(), width.toByte(), 3) +
        ByteArray(9) +
        byteArrayOf(0xFF.toByte(), 0xD9.toByte())

private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10)

/**
 * Fully checks a PNG: chunk CRCs, IHDR, and that the IDAT stream inflates to the expected
 * scanline bytes (8-bit, non-interlaced). Returns the size, or null when it does not decode.
 */
internal fun decodePngOrNull(bytes: ByteArray): PixelSize? {
    if (bytes.size < 8 || !bytes.copyOfRange(0, 8).contentEquals(PNG_SIGNATURE)) return null
    val buffer = ByteBuffer.wrap(bytes, 8, bytes.size - 8)
    var size: PixelSize? = null
    var channels = 0
    var bitDepth = 0
    var interlaced = false
    val idat = ByteArrayOutputStream()
    while (buffer.remaining() >= 12) {
        val length = buffer.int
        val type = ByteArray(4).also(buffer::get)
        if (length < 0 || length > buffer.remaining() - 4) return null
        val data = ByteArray(length).also(buffer::get)
        val crc = buffer.int
        if (CRC32()
                .apply { update(type) }
                .apply { update(data) }
                .value
                .toInt() != crc
        ) {
            return null
        }
        when (String(type, Charsets.US_ASCII)) {
            "IHDR" -> {
                val header = ByteBuffer.wrap(data)
                size = PixelSize(header.int, header.int)
                bitDepth = header.get().toInt()
                channels =
                    when (header.get().toInt()) {
                        0 -> 1
                        2 -> 3
                        3 -> 1
                        4 -> 2
                        6 -> 4
                        else -> return null
                    }
                header.get()
                header.get()
                interlaced = header.get().toInt() != 0
            }
            "IDAT" -> idat.write(data)
            "IEND" -> break
        }
    }
    val pixelSize = size ?: return null
    val inflater = Inflater().apply { setInput(idat.toByteArray()) }
    val out = ByteArray(64 * 1024)
    var total = 0L
    while (!inflater.finished()) {
        val read = inflater.inflate(out)
        if (read == 0 && (inflater.needsInput() || inflater.needsDictionary())) return null
        total += read
    }
    val expected = pixelSize.height.toLong() * (1 + (pixelSize.width.toLong() * channels * bitDepth + 7) / 8)
    return if (interlaced || bitDepth != 8 || total == expected) pixelSize else null
}

/** The value of one multipart form field (OkHttp adds a Content-Length header per part). */
internal fun multipartField(
    body: String,
    name: String,
): String? = Regex("""name="${Regex.escape(name)}"\r\n(?:[^\r\n]+\r\n)*\r\n([^\r\n]*)\r\n""").find(body)?.groupValues?.get(1)

internal fun imagesResponseJson(
    bytes: ByteArray,
    outputFormat: String = "png",
): String = """{"created":1,"output_format":"$outputFormat","data":[{"b64_json":"${Base64.getEncoder().encodeToString(bytes)}"}]}"""

internal fun videoJson(
    id: String,
    status: String,
    extra: String = "",
): String = """{"id":"$id","object":"video","model":"sora-2","status":"$status","progress":50,"seconds":"4","size":"720x1280"$extra}"""
