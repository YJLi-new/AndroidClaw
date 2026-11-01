package ai.androidclaw.runtime.providers

import ai.androidclaw.data.ProviderEndpointSettings
import ai.androidclaw.data.ProviderSettingsSnapshot
import ai.androidclaw.data.ProviderType
import ai.androidclaw.data.SettingsDataStore
import ai.androidclaw.testutil.InMemoryProviderSecretStore
import ai.androidclaw.testutil.MainDispatcherRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.TimeUnit

/** Request encoding of user images for the three remote wire formats, plus the 4xx mapping. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class ProviderImageEncodingTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var settingsDataStore: SettingsDataStore
    private lateinit var secretStore: InMemoryProviderSecretStore
    private lateinit var server: MockWebServer
    private lateinit var tempDir: File
    private val json = Json { ignoreUnknownKeys = true }
    private val imageBytes = byteArrayOf(-1, -40, -1, -32, 1, 2, 3, 4)

    @Before
    fun setUp() =
        runTest {
            settingsDataStore = SettingsDataStore(ApplicationProvider.getApplicationContext())
            secretStore = InMemoryProviderSecretStore()
            server = MockWebServer()
            server.start()
            tempDir = Files.createTempDirectory("img-enc").toFile()
            val baseUrl = server.url("/v1/").toString().removeSuffix("/")
            var snapshot = ProviderSettingsSnapshot()
            listOf(ProviderType.OpenAi, ProviderType.DeepSeek, ProviderType.Anthropic).forEach { type ->
                snapshot = snapshot.withEndpointSettings(type, ProviderEndpointSettings(baseUrl, "m", 5, acceptsImages = true))
                secretStore.writeApiKey(type, "key")
            }
            settingsDataStore.saveProviderSettings(snapshot)
        }

    @After
    fun tearDown() =
        runTest {
            settingsDataStore.saveProviderSettings(ProviderSettingsSnapshot())
            secretStore.clear()
            server.shutdown()
            tempDir.deleteRecursively()
        }

    @Test
    fun `responses api sends marker then input_image data url`() =
        runTest {
            server.enqueue(jsonResponse(RESPONSES_REPLY))
            openAi(responses = true).generate(request())
            val content = lastUserContent(server.takeBody(), "input")
            assertEquals("input_text", content[0].type())
            assertEquals("What is this?", content[0].string("text"))
            assertEquals("[attachment a1: cat.jpg 800×600]", content[1].string("text"))
            assertEquals("input_image", content[2].type())
            assertEquals(expectedDataUrl(), content[2].string("image_url"))
            // The second image is outside the bound (no file): marker only.
            assertEquals("[attachment a2: old.jpg 10×10]", content[3].string("text"))
            assertEquals(4, content.size)
        }

    @Test
    fun `chat completions sends text and image_url parts`() =
        runTest {
            server.enqueue(jsonResponse(CHAT_REPLY))
            openAi(responses = false, type = ProviderType.DeepSeek).generate(request())
            val content = lastUserContent(server.takeBody(), "messages")
            assertEquals("text", content[0].type())
            assertEquals("text", content[1].type())
            assertEquals("image_url", content[2].type())
            assertEquals(expectedDataUrl(), content[2]["image_url"]!!.jsonObject.string("url"))
        }

    @Test
    fun `chat completions without inline images keeps a plain string with markers`() =
        runTest {
            server.enqueue(jsonResponse(CHAT_REPLY))
            val textOnly =
                request().let { request ->
                    request.copy(
                        messageHistory =
                            request.messageHistory.map { message ->
                                message.copy(attachments = message.attachments.map { it.copy(file = null) })
                            },
                    )
                }
            openAi(responses = false, type = ProviderType.DeepSeek).generate(textOnly)
            val payload = server.takeBody()
            val user =
                payload["messages"]!!.jsonArray.last { it.jsonObject.string("role") == "user" }.jsonObject
            assertEquals(
                "What is this?\n[attachment a1: cat.jpg 800×600]\n[attachment a2: old.jpg 10×10]",
                user.string("content"),
            )
        }

    @Test
    fun `claude sends base64 image blocks after markers`() =
        runTest {
            server.enqueue(jsonResponse(CLAUDE_REPLY))
            AnthropicProvider(settingsDataStore, secretStore, createProviderBaseHttpClient(), json).generate(request())
            val content = lastUserContent(server.takeBody(), "messages")
            assertEquals("text", content[0].type())
            assertEquals("[attachment a1: cat.jpg 800×600]", content[1].string("text"))
            assertEquals("image", content[2].type())
            val source = content[2]["source"]!!.jsonObject
            assertEquals("base64", source.string("type"))
            assertEquals("image/jpeg", source.string("media_type"))
            assertEquals(Base64.getEncoder().encodeToString(imageBytes), source.string("data"))
        }

    @Test
    fun `4xx naming images maps to ImagesUnsupported`() =
        runTest {
            server.enqueue(
                MockResponse()
                    .setResponseCode(400)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"error":{"message":"This model does not support image input."}}"""),
            )
            val error =
                runCatching { openAi(responses = false, type = ProviderType.DeepSeek).generate(request()) }
                    .exceptionOrNull() as ModelProviderException
            assertEquals(ModelProviderFailureKind.ImagesUnsupported, error.kind)
        }

    @Test
    fun `image rejection detection ignores auth and unrelated errors`() {
        assertTrue(isProviderImageRejection(400, "Invalid image_url content"))
        assertTrue(isProviderImageRejection(422, "Model does not support vision"))
        assertTrue(!isProviderImageRejection(401, "image key invalid"))
        assertTrue(!isProviderImageRejection(429, "image rate limit"))
        assertTrue(!isProviderImageRejection(400, "max_tokens is too large"))
        assertTrue(!isProviderImageRejection(500, "image service down"))
    }

    private fun openAi(
        responses: Boolean,
        type: ProviderType = ProviderType.OpenAi,
    ) = OpenAiCompatibleProvider(
        providerType = type,
        settingsDataStore = settingsDataStore,
        providerSecretStore = secretStore,
        baseHttpClient = createProviderBaseHttpClient(),
        json = json,
        useResponsesApi = { responses },
    )

    private fun request(): ModelRequest {
        val file = File(tempDir, "a1.jpg").apply { writeBytes(imageBytes) }
        return ModelRequest(
            sessionId = "s",
            requestId = "r",
            messageHistory =
                listOf(
                    ModelMessage(
                        role = ModelMessageRole.User,
                        content = "What is this?",
                        attachments =
                            listOf(
                                ModelAttachment("a1", "cat.jpg", "image/jpeg", 800, 600, imageBytes.size.toLong(), "s/a1.jpg", file),
                                ModelAttachment("a2", "old.jpg", "image/jpeg", 10, 10, 5, "s/a2.jpg", null),
                            ),
                    ),
                ),
            systemPrompt = "sys",
            enabledSkills = emptyList(),
            toolDescriptors = emptyList(),
            runMode = ModelRunMode.Interactive,
        )
    }

    private fun expectedDataUrl() = "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(imageBytes)

    private fun MockWebServer.takeBody(): JsonObject = json.parseToJsonElement(takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8()).jsonObject

    private fun lastUserContent(
        payload: JsonObject,
        field: String,
    ): List<JsonObject> =
        payload[field]!!
            .jsonArray
            .last { it.jsonObject.string("role") == "user" }
            .jsonObject["content"]!!
            .jsonArray
            .map { it.jsonObject }

    private fun JsonObject.string(name: String): String = getValue(name).jsonPrimitive.content

    private fun JsonObject.type(): String = string("type")

    private fun jsonResponse(body: String) =
        MockResponse()
            .setHeader("Content-Type", "application/json")
            .setBody(body)

    private companion object {
        const val RESPONSES_REPLY =
            """{"id":"resp-1","model":"m","status":"completed","output":[{"type":"message","id":"msg-1","role":"assistant","content":[{"type":"output_text","text":"A cat."}]}]}"""
        const val CHAT_REPLY =
            """{"id":"c1","model":"m","choices":[{"message":{"role":"assistant","content":"A cat."},"finish_reason":"stop"}]}"""
        const val CLAUDE_REPLY =
            """{"id":"msg_1","model":"m","content":[{"type":"text","text":"A cat."}],"stop_reason":"end_turn"}"""
    }
}
