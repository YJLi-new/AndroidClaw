package ai.androidclaw.data

import ai.androidclaw.testutil.InMemoryProviderSecretStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProviderSecretStoreTest {
    @Test
    fun `api keys are trimmed bounded and blank values clear`() =
        runTest {
            val store = InMemoryProviderSecretStore()
            val apiKey = "sk-" + "k".repeat(PROVIDER_API_KEY_MAX_CHARS + 20)

            store.writeApiKey(ProviderType.OpenAiCompatible, "  $apiKey  ")

            assertEquals(apiKey.take(PROVIDER_API_KEY_MAX_CHARS), store.readApiKey(ProviderType.OpenAiCompatible))

            store.writeApiKey(ProviderType.OpenAiCompatible, "   ")

            assertNull(store.readApiKey(ProviderType.OpenAiCompatible))
        }

    @Test
    fun `oauth credentials are trimmed bounded and fallback provider id is filled`() =
        runTest {
            val accessToken = "access-" + "a".repeat(PROVIDER_OAUTH_TOKEN_MAX_CHARS + 20)
            val refreshToken = "refresh-" + "r".repeat(PROVIDER_OAUTH_TOKEN_MAX_CHARS + 20)
            val email = "codex@example.test" + "e".repeat(PROVIDER_OAUTH_PROFILE_FIELD_MAX_CHARS + 20)
            val profileName = "Codex User " + "p".repeat(PROVIDER_OAUTH_PROFILE_FIELD_MAX_CHARS + 20)
            val accountId = "account-" + "c".repeat(PROVIDER_OAUTH_PROFILE_FIELD_MAX_CHARS + 20)
            val store = InMemoryProviderSecretStore()

            store.writeOAuthCredential(
                ProviderType.OpenAiCodex,
                ProviderOAuthCredential(
                    provider = "   ",
                    accessToken = "  $accessToken  ",
                    refreshToken = "  $refreshToken  ",
                    expiresAtEpochMillis = 1_800_000_000_000L,
                    email = "  $email  ",
                    profileName = "  $profileName  ",
                    chatGptAccountId = "  $accountId  ",
                ),
            )

            val stored = store.readOAuthCredential(ProviderType.OpenAiCodex)

            assertEquals(ProviderType.OpenAiCodex.providerId, stored?.provider)
            assertEquals(accessToken.take(PROVIDER_OAUTH_TOKEN_MAX_CHARS), stored?.accessToken)
            assertEquals(refreshToken.take(PROVIDER_OAUTH_TOKEN_MAX_CHARS), stored?.refreshToken)
            assertEquals(email.take(PROVIDER_OAUTH_PROFILE_FIELD_MAX_CHARS), stored?.email)
            assertEquals(profileName.take(PROVIDER_OAUTH_PROFILE_FIELD_MAX_CHARS), stored?.profileName)
            assertEquals(accountId.take(PROVIDER_OAUTH_PROFILE_FIELD_MAX_CHARS), stored?.chatGptAccountId)
        }

    @Test
    fun `oauth credentials with blank required tokens clear stored value`() =
        runTest {
            val store =
                InMemoryProviderSecretStore(
                    initialOAuthCredentials =
                        mapOf(
                            ProviderType.OpenAiCodex to
                                ProviderOAuthCredential(
                                    provider = ProviderType.OpenAiCodex.providerId,
                                    accessToken = "access",
                                    refreshToken = "refresh",
                                    expiresAtEpochMillis = 1_800_000_000_000L,
                                ),
                        ),
                )

            store.writeOAuthCredential(
                ProviderType.OpenAiCodex,
                ProviderOAuthCredential(
                    provider = ProviderType.OpenAiCodex.providerId,
                    accessToken = "   ",
                    refreshToken = "refresh",
                    expiresAtEpochMillis = 1_800_000_000_000L,
                ),
            )

            assertNull(store.readOAuthCredential(ProviderType.OpenAiCodex))
        }

    @Test
    fun `oauth credential normalization returns null for blank required tokens`() {
        val normalized =
            ProviderOAuthCredential(
                provider = ProviderType.OpenAiCodex.providerId,
                accessToken = "access",
                refreshToken = "   ",
                expiresAtEpochMillis = 1_800_000_000_000L,
            ).toNormalizedProviderOAuthCredential(ProviderType.OpenAiCodex)

        assertNull(normalized)
    }
}
