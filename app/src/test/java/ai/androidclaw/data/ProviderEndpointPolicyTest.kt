package ai.androidclaw.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProviderEndpointPolicyTest {
    @Test
    fun `endpoint policy rejects malformed or credential-bearing base urls`() {
        val invalidScheme =
            ProviderEndpointSettings(
                baseUrl = "ftp://api.example.test/v1",
                modelId = "model",
                timeoutSeconds = DEFAULT_PROVIDER_TIMEOUT_SECONDS,
            ).firstProviderEndpointPolicyError(ProviderType.OpenAiCompatible)
        val credentialUrl =
            ProviderEndpointSettings(
                baseUrl = "https://token@example.test/v1",
                modelId = "model",
                timeoutSeconds = DEFAULT_PROVIDER_TIMEOUT_SECONDS,
            ).firstProviderEndpointPolicyError(ProviderType.OpenAiCompatible)

        assertEquals("PROVIDER_BASE_URL_UNSUPPORTED_SCHEME", invalidScheme?.code)
        assertEquals("PROVIDER_BASE_URL_USERINFO", credentialUrl?.code)
    }

    @Test
    fun `endpoint policy warns on non loopback http but allows local http`() {
        val remoteHttpIssues =
            ProviderEndpointSettings(
                baseUrl = "http://api.example.test/v1",
                modelId = "model",
                timeoutSeconds = DEFAULT_PROVIDER_TIMEOUT_SECONDS,
            ).providerEndpointPolicyIssues(ProviderType.OpenAiCompatible)
        val localHttpError =
            ProviderEndpointSettings(
                baseUrl = "http://127.0.0.1:11434/v1",
                modelId = "model",
                timeoutSeconds = DEFAULT_PROVIDER_TIMEOUT_SECONDS,
            ).firstProviderEndpointPolicyError(ProviderType.OpenAiCompatible)

        assertEquals("PROVIDER_BASE_URL_INSECURE_HTTP", remoteHttpIssues.single().code)
        assertEquals(ProviderEndpointPolicySeverity.Warning, remoteHttpIssues.single().severity)
        assertNull(localHttpError)
    }
}
