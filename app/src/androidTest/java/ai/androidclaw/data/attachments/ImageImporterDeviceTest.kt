package ai.androidclaw.data.attachments

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.media.ExifInterface
import android.net.Uri
import android.os.Debug
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * Device check for the import pipeline on a generated 12 MP (4000×3000) JPEG with EXIF
 * orientation 6: output size and bytes, import time, and the peak Java heap delta while importing.
 * Prints one `MEDIA_IMPORT|...` logcat line with the readouts.
 */
@RunWith(AndroidJUnit4::class)
class ImageImporterDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var workDir: File
    private lateinit var store: AttachmentStore

    @Before
    fun setUp() {
        workDir = File(context.cacheDir, "import-device-test").apply { deleteRecursively() }
        workDir.mkdirs()
        store = AttachmentStore(File(workDir, "attachments"))
    }

    @After
    fun tearDown() {
        workDir.deleteRecursively()
    }

    @Test
    fun import12MegapixelPhoto() =
        runBlocking {
            val source = File(workDir, "photo-12mp.jpg")
            writeSamplePhoto(source)
            ExifInterface(source.path).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
                setAttribute(ExifInterface.TAG_GPS_LATITUDE, "52/1,30/1,0/1")
                saveAttributes()
            }
            val importer = AndroidImageImporter(context.contentResolver, store)

            val runtime = Runtime.getRuntime()
            System.gc()
            Thread.sleep(200)
            val javaBaseline = runtime.totalMemory() - runtime.freeMemory()
            val nativeBaseline = Debug.getNativeHeapAllocatedSize()
            val javaPeak = AtomicLong(javaBaseline)
            val nativePeak = AtomicLong(nativeBaseline)
            val sampling =
                java.util.concurrent.atomic
                    .AtomicBoolean(true)
            val sampler =
                thread(name = "heap-sampler") {
                    while (sampling.get()) {
                        javaPeak.accumulateAndGet(runtime.totalMemory() - runtime.freeMemory(), ::maxOf)
                        nativePeak.accumulateAndGet(Debug.getNativeHeapAllocatedSize(), ::maxOf)
                        Thread.sleep(2)
                    }
                }
            val started = System.nanoTime()
            val staged = importer.importImage("s1", Uri.fromFile(source))
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            sampling.set(false)
            sampler.join()

            val output = store.file(staged.relativePath)
            val thumbnail = store.thumbnailFile(staged.relativePath)
            val javaDeltaMb = (javaPeak.get() - javaBaseline) / (1024.0 * 1024.0)
            val nativeDeltaMb = (nativePeak.get() - nativeBaseline) / (1024.0 * 1024.0)
            val thumbBounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(thumbnail.path, thumbBounds)
            Log.i(
                "MediaImport",
                "MEDIA_IMPORT|source=4000x3000 ${source.length()}B|out=${staged.width}x${staged.height} ${staged.byteCount}B|" +
                    "thumb=${thumbBounds.outWidth}x${thumbBounds.outHeight}|ms=$elapsedMs|" +
                    "javaPeakDeltaMB=${"%.1f".format(javaDeltaMb)}|nativePeakDeltaMB=${"%.1f".format(nativeDeltaMb)}",
            )

            // EXIF orientation 6 applied: portrait output with the long side at 1568.
            assertEquals(1176, staged.width)
            assertEquals(1568, staged.height)
            assertTrue(staged.byteCount <= IMPORT_MAX_BYTES)
            assertEquals(output.length(), staged.byteCount)
            assertEquals(IMPORT_THUMBNAIL_LONG_SIDE_PX, maxOf(thumbBounds.outWidth, thumbBounds.outHeight))
            // Re-encoding drops metadata (GPS) and the orientation tag.
            val outExif = ExifInterface(output.path)
            assertEquals(null, outExif.getAttribute(ExifInterface.TAG_GPS_LATITUDE))
            assertTrue(outExif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) <= 1)
            assertTrue("Java heap delta ${"%.1f".format(javaDeltaMb)} MB", javaDeltaMb <= 32.0)
        }

    private fun writeSamplePhoto(file: File) {
        val bitmap = Bitmap.createBitmap(4000, 3000, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(0f, 0f, 4000f, 3000f, Color.rgb(30, 90, 160), Color.rgb(240, 200, 80), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, 4000f, 3000f, paint)
        paint.shader = null
        val random = java.util.Random(13)
        repeat(400) {
            paint.color = Color.rgb(random.nextInt(256), random.nextInt(256), random.nextInt(256))
            val x = random.nextInt(4000).toFloat()
            val y = random.nextInt(3000).toFloat()
            canvas.drawCircle(x, y, 20f + random.nextInt(200), paint)
        }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        bitmap.recycle()
    }
}
