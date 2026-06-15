package ai.androidclaw.runtime.providers

import ai.androidclaw.data.ProviderOAuthCredential
import ai.androidclaw.data.ProviderType
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import java.util.concurrent.TimeUnit

class OpenAiCodexOAuthClientTest {
    private lateinit var server: MockWebServer
    private val json = Json { ignoreUnknownKeys = true }
    private val clock =
        Clock.fixed(
            Instant.parse("2026-04-27T00:00:00Z"),
            ZoneOffset.UTC,
        )

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `device code login stores oauth credential identity and expiry`() =
        runTest {
            val accessToken =
                fakeJwt(
                    email = "codex@example.test",
                    accountId = "acct-123",
                    expiresAtEpochSeconds = 1_800_000_000,
                )
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody(
                        """
                        {
                          "device_auth_id": "device-123",
                          "user_code": "ABCD-EFGH",
                          "interval": 1
                        }
                        """.trimIndent(),
                    ),
            )
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody(
                        """
                        {
                          "authorization_code": "auth-code",
                          "code_verifier": "verifier"
                        }
                        """.trimIndent(),
                    ),
            )
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody(
                        """
                        {
                          "access_token": "$accessToken",
                          "refresh_token": "refresh-token",
                          "expires_in": 3600
                        }
                        """.trimIndent(),
                    ),
            )
            val prompts = mutableListOf<OpenAiCodexDeviceCodePrompt>()

            val credential =
                buildClient().loginWithDeviceCode(
                    onVerification = { prompts += it },
                )

            assertEquals(ProviderType.OpenAiCodex.providerId, credential.provider)
            assertEquals(accessToken, credential.accessToken)
            assertEquals("refresh-token", credential.refreshToken)
            assertEquals("codex@example.test", credential.email)
            assertEquals("acct-123", credential.chatGptAccountId)
            assertEquals(clock.millis() + 3_600_000, credential.expiresAtEpochMillis)
            assertEquals("ABCD-EFGH", prompts.single().userCode)
            assertEquals("/api/accounts/deviceauth/usercode", server.takeRequest(5, TimeUnit.SECONDS)!!.path)
            assertEquals("/api/accounts/deviceauth/token", server.takeRequest(5, TimeUnit.SECONDS)!!.path)
            assertEquals("/oauth/token", server.takeRequest(5, TimeUnit.SECONDS)!!.path)
        }

    @Test
    fun `refresh credential keeps old refresh token when response omits one`() =
        runTest {
            val accessToken =
                fakeJwt(
                    email = "fresh@example.test",
                    accountId = "acct-fresh",
                    expiresAtEpochSeconds = 1_800_000_000,
                )
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody(
                        """
                        {
                          "access_token": "$accessToken",
                          "expires_in": 120
                        }
                        """.trimIndent(),
                    ),
            )

            val refreshed =
                buildClient().refreshCredential(
                    ProviderOAuthCredential(
                        provider = ProviderType.OpenAiCodex.providerId,
                        accessToken = "old-access",
                        refreshToken = "old-refresh",
                        expiresAtEpochMillis = clock.millis() - 1,
                    ),
                )
            val request = server.takeRequest(5, TimeUnit.SECONDS)!!
            val body = request.body.readUtf8()

            assertEquals(accessToken, refreshed.accessToken)
            assertEquals("old-refresh", refreshed.refreshToken)
            assertEquals("fresh@example.test", refreshed.email)
            assertEquals("acct-fresh", refreshed.chatGptAccountId)
            assertEquals(clock.millis() + 120_000, refreshed.expiresAtEpochMillis)
            assertEquals("/oauth/token", request.path)
            assertTrue(body.contains("grant_type=refresh_token"))
            assertTrue(body.contains("refresh_token=old-refresh"))
        }

    @Test
    fun `device code failure sanitizes provider error`() =
        runTest {
            server.enqueue(
                MockResponse()
                    .setResponseCode(403)
                    .setBody("""{"error":"invalid_request","error_description":"bad\nregion"}"""),
            )

            val error =
                runCatching {
                    buildClient().loginWithDeviceCode(onVerification = {})
                }.exceptionOrNull()

            assertTrue(error is ModelProviderException)
            val message = error!!.message.orEmpty()
            assertTrue(message.contains("invalid_request"))
            assertFalse(message.contains('\n'))
        }

    @Test
    fun `device code failure surfaces root level provider message`() =
        runTest {
            server.enqueue(
                MockResponse()
                    .setResponseCode(400)
                    .setBody("""{"message":"region blocked\nfor device auth"}"""),
            )

            val error =
                runCatching {
                    buildClient().loginWithDeviceCode(onVerification = {})
                }.exceptionOrNull()

            assertTrue(error is ModelProviderException)
            val message = error!!.message.orEmpty()
            assertTrue(message.contains("OpenAI device code request failed"))
            assertTrue(message.contains("region blocked for device auth"))
            assertFalse(message.contains('\n'))
            assertFalse(message.contains("{"))
        }

    @Test
    fun `device code failure surfaces bare oauth error description`() =
        runTest {
            server.enqueue(
                MockResponse()
                    .setResponseCode(400)
                    .setBody("""{"error_description":"device auth disabled\nfor tenant"}"""),
            )

            val error =
                runCatching {
                    buildClient().loginWithDeviceCode(onVerification = {})
                }.exceptionOrNull()

            assertTrue(error is ModelProviderException)
            val message = error!!.message.orEmpty()
            assertTrue(message.contains("OpenAI device code request failed"))
            assertTrue(message.contains("device auth disabled for tenant"))
            assertFalse(message.contains('\n'))
            assertFalse(message.contains("{"))
        }

    @Test
    fun `device code failure surfaces object provider error message`() =
        runTest {
            server.enqueue(
                MockResponse()
                    .setResponseCode(400)
                    .setBody("""{"error":{"message":"invalid OAuth\nclient"}}"""),
            )

            val error =
                runCatching {
                    buildClient().loginWithDeviceCode(onVerification = {})
                }.exceptionOrNull()

            assertTrue(error is ModelProviderException)
            val message = error!!.message.orEmpty()
            assertTrue(message.contains("OpenAI device code request failed"))
            assertTrue(message.contains("invalid OAuth client"))
            assertFalse(message.contains('\n'))
            assertFalse(message.contains("{"))
        }

    @Test
    fun `token refresh failure surfaces root level provider detail`() =
        runTest {
            server.enqueue(
                MockResponse()
                    .setResponseCode(400)
                    .setBody("""{"detail":"refresh token expired\nsign in again"}"""),
            )

            val error =
                runCatching {
                    buildClient().refreshCredential(
                        ProviderOAuthCredential(
                            provider = ProviderType.OpenAiCodex.providerId,
                            accessToken = "old-access",
                            refreshToken = "old-refresh",
                            expiresAtEpochMillis = clock.millis() - 1,
                        ),
                    )
                }.exceptionOrNull()

            assertTrue(error is ModelProviderException)
            val message = error!!.message.orEmpty()
            assertTrue(message.contains("OpenAI token refresh failed"))
            assertTrue(message.contains("refresh token expired sign in again"))
            assertFalse(message.contains('\n'))
            assertFalse(message.contains("{"))
        }

    @Test
    fun `token refresh failure surfaces object provider error detail`() =
        runTest {
            server.enqueue(
                MockResponse()
                    .setResponseCode(400)
                    .setBody("""{"error":{"detail":"refresh disabled\nfor account"}}"""),
            )

            val error =
                runCatching {
                    buildClient().refreshCredential(
                        ProviderOAuthCredential(
                            provider = ProviderType.OpenAiCodex.providerId,
                            accessToken = "old-access",
                            refreshToken = "old-refresh",
                            expiresAtEpochMillis = clock.millis() - 1,
                        ),
                    )
                }.exceptionOrNull()

            assertTrue(error is ModelProviderException)
            val message = error!!.message.orEmpty()
            assertTrue(message.contains("OpenAI token refresh failed"))
            assertTrue(message.contains("refresh disabled for account"))
            assertFalse(message.contains('\n'))
            assertFalse(message.contains("{"))
        }

    private fun buildClient(): HttpOpenAiCodexOAuthClient =
        HttpOpenAiCodexOAuthClient(
            httpClient = createProviderBaseHttpClient(),
            json = json,
            clock = clock,
            authBaseUrl = server.url("/").toString().removeSuffix("/"),
            pollDelay = {},
        )

    private fun fakeJwt(
        email: String,
        accountId: String,
        expiresAtEpochSeconds: Long,
    ): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val header = encoder.encodeToString("""{"alg":"none"}""".toByteArray())
        val payload =
            encoder.encodeToString(
                """
                {
                  "exp": $expiresAtEpochSeconds,
                  "sub": "user-123",
                  "https://api.openai.com/auth": {
                    "chatgpt_account_id": "$accountId"
                  },
                  "https://api.openai.com/profile": {
                    "email": "$email"
                  }
                }
                """.trimIndent().toByteArray(),
            )
        return "$header.$payload.signature"
    }
}
