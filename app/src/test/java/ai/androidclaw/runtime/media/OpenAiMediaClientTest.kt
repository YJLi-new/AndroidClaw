package ai.androidclaw.runtime.media

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/** MockWebServer contracts for OpenAI Images (JSON generations, multipart edits) and Videos. */
class OpenAiMediaClientTest {
    private lateinit var server: MockWebServer
    private lateinit var credentials: OpenAiMediaCredentials
    private val client = OpenAiMediaClient()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        credentials = OpenAiMediaCredentials(apiKey = "sk-test", baseUrl = server.url("/v1").toString())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `generation posts JSON and decodes b64_json`() =
        runBlocking {
            val png = testImageBytes(32, 16)
            server.enqueue(MockResponse().setBody(imagesResponseJson(png)))

            val outcome = client.generateImage(credentials, "gpt-image-x", "a red apple", "1024x1024", "low")

            val success = outcome as ImageOutcome.Success
            assertArrayEquals(png, success.bytes)
            assertEquals("image/png", success.mimeType)
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/v1/images/generations", request.path)
            assertEquals("Bearer sk-test", request.getHeader("Authorization"))
            val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
            assertEquals("gpt-image-x", body["model"]!!.jsonPrimitive.content)
            assertEquals("a red apple", body["prompt"]!!.jsonPrimitive.content)
            assertEquals("1024x1024", body["size"]!!.jsonPrimitive.content)
            assertEquals("low", body["quality"]!!.jsonPrimitive.content)
            assertEquals("1", body["n"]!!.jsonPrimitive.content)
        }

    @Test
    fun `generation omits quality when not given and maps jpeg output`() =
        runBlocking {
            val jpeg = testImageBytes(8, 8, "jpg")
            server.enqueue(MockResponse().setBody(imagesResponseJson(jpeg, outputFormat = "jpeg")))

            val outcome = client.generateImage(credentials, "m", "p", "1024x1024", null) as ImageOutcome.Success

            assertEquals("image/jpeg", outcome.mimeType)
            assertFalse(
                server
                    .takeRequest()
                    .body
                    .readUtf8()
                    .contains("quality"),
            )
        }

    @Test
    fun `edit sends multipart images and fields`() =
        runBlocking {
            val first = testImageBytes(8, 8)
            val second = testImageBytes(4, 4, "jpg")
            server.enqueue(MockResponse().setBody(imagesResponseJson(testImageBytes(16, 16))))

            val outcome =
                client.editImage(
                    credentials,
                    "gpt-image-x",
                    "make it green",
                    "1024x1024",
                    null,
                    listOf(ImageInput("a.png", "image/png", first), ImageInput("b.jpg", "image/jpeg", second)),
                )

            assertTrue(outcome is ImageOutcome.Success)
            val request = server.takeRequest()
            assertEquals("/v1/images/edits", request.path)
            assertTrue(request.getHeader("Content-Type")!!.startsWith("multipart/form-data"))
            val body = request.body.readString(Charsets.ISO_8859_1)
            assertEquals("gpt-image-x", multipartField(body, "model"))
            assertEquals("make it green", multipartField(body, "prompt"))
            assertEquals("1024x1024", multipartField(body, "size"))
            assertFalse(body.contains("name=\"quality\""))
            assertEquals(2, Regex("name=\"image\\[]\"").findAll(body).count())
            assertTrue(body.contains("filename=\"a.png\"") && body.contains("filename=\"b.jpg\""))
            assertTrue(body.contains("Content-Type: image/jpeg"))
        }

    @Test
    fun `http error keeps the status and the API message`() =
        runBlocking {
            server.enqueue(
                MockResponse()
                    .setResponseCode(400)
                    .setBody("""{"error":{"message":"Your request was rejected by the safety system.","type":"image_generation_user_error"}}"""),
            )

            val failure = client.generateImage(credentials, "m", "p", null, null) as ImageOutcome.Failure

            assertEquals("HTTP_ERROR", failure.errorCode)
            assertEquals(400, failure.httpStatus)
            assertTrue(failure.message, failure.message.contains("safety system"))
        }

    @Test
    fun `missing image data is a structured failure`() =
        runBlocking {
            server.enqueue(MockResponse().setBody("""{"data":[]}"""))
            val failure = client.generateImage(credentials, "m", "p", null, null) as ImageOutcome.Failure
            assertEquals("FETCH_FAILED", failure.errorCode)
        }

    @Test
    fun `cancelling the coroutine cancels the HTTP call`() =
        runBlocking {
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val call =
                async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                    client.generateImage(credentials, "m", "p", null, null)
                }
            server.takeRequest(5, TimeUnit.SECONDS)
            val started = System.nanoTime()
            withTimeout(5_000) {
                call.cancel()
                call.join()
            }
            assertTrue(call.isCancelled)
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 5_000)
        }

    @Test
    fun `video create sends multipart fields and parses the job`() =
        runBlocking {
            server.enqueue(MockResponse().setBody(videoJson("video_1", "queued")))

            val job = (client.createVideo(credentials, "sora-2", "a cat surfing", "4", "720x1280") as VideoOutcome.Success).job

            assertEquals("video_1", job.id)
            assertEquals(VideoJobStatus.Queued, job.status)
            val request = server.takeRequest()
            assertEquals("/v1/videos", request.path)
            val body = request.body.readUtf8()
            assertEquals("sora-2", multipartField(body, "model"))
            assertEquals("a cat surfing", multipartField(body, "prompt"))
            assertEquals("4", multipartField(body, "seconds"))
            assertEquals("720x1280", multipartField(body, "size"))
        }

    @Test
    fun `video retrieve maps in progress, completed and failed`() =
        runBlocking {
            server.enqueue(MockResponse().setBody(videoJson("v", "in_progress")))
            server.enqueue(MockResponse().setBody(videoJson("v", "completed")))
            server.enqueue(MockResponse().setBody(videoJson("v", "failed", ""","error":{"code":"x","message":"moderation"}""")))

            assertEquals(VideoJobStatus.InProgress, (client.retrieveVideo(credentials, "v") as VideoOutcome.Success).job.status)
            assertEquals(VideoJobStatus.Completed, (client.retrieveVideo(credentials, "v") as VideoOutcome.Success).job.status)
            val failed = (client.retrieveVideo(credentials, "v") as VideoOutcome.Success).job
            assertEquals(VideoJobStatus.Failed, failed.status)
            assertEquals("moderation", failed.errorMessage)
            assertEquals("/v1/videos/v", server.takeRequest().path)
        }

    @Test
    fun `video download streams into the file and enforces the size bound`() =
        runBlocking {
            val dir = Files.createTempDirectory("video-dl").toFile()
            try {
                val bytes = ByteArray(10_000) { (it % 251).toByte() }
                server.enqueue(MockResponse().setBody(Buffer().write(bytes)).setHeader("Content-Type", "video/mp4"))
                server.enqueue(MockResponse().setBody(Buffer().write(bytes)))
                val target = dir.resolve("s/v.mp4")

                val ok = client.downloadVideo(credentials, "v", target) as DownloadOutcome.Success
                assertEquals(10_000L, ok.byteCount)
                assertArrayEquals(bytes, target.readBytes())
                assertEquals("/v1/videos/v/content", server.takeRequest().path)

                val tooLarge = client.downloadVideo(credentials, "v", target, maxBytes = 1_000) as DownloadOutcome.Failure
                assertEquals("TOO_LARGE", tooLarge.errorCode)
                assertFalse(target.exists())
            } finally {
                dir.deleteRecursively()
            }
        }
}
