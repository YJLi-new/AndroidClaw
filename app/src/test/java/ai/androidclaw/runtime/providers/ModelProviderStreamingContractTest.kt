package ai.androidclaw.runtime.providers

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ModelProviderStreamingContractTest {
    @Test
    fun `default streamGenerate falls back to generate`() = runTest {
        val provider = object : ModelProvider {
            override val id: String = "stub"

            override suspend fun generate(request: ModelRequest): ModelResponse {
                return ModelResponse(
                    text = "hello from generate",
                    providerRequestId = "req-1",
                )
            }
        }

        val events = provider.streamGenerate(buildRequest()).toList()

        assertEquals(ProviderCapabilities(), provider.capabilities)
        assertEquals(
            listOf(
                ModelStreamEvent.Completed(
                    ModelResponse(
                        text = "hello from generate",
                        providerRequestId = "req-1",
                    ),
                ),
            ),
            events,
        )
    }

    private fun buildRequest(): ModelRequest {
        return ModelRequest(
            sessionId = "session-1",
            requestId = "request-1",
            messageHistory = listOf(
                ModelMessage(
                    role = ModelMessageRole.User,
                    content = "hello",
                ),
            ),
            systemPrompt = "prompt",
            enabledSkills = emptyList(),
            toolDescriptors = emptyList(),
            runMode = ModelRunMode.Interactive,
        )
    }
}
