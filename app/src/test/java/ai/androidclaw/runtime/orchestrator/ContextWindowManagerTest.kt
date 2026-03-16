package ai.androidclaw.runtime.orchestrator

import ai.androidclaw.runtime.providers.ModelMessage
import ai.androidclaw.runtime.providers.ModelMessageRole
import ai.androidclaw.runtime.providers.ProviderToolCall
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextWindowManagerTest {
    @Test
    fun `short history is preserved without truncation`() {
        val manager = ContextWindowManager(promptBudgetUnits = 256)

        val selection = manager.select(
            systemPrompt = "short prompt",
            persistedHistory = listOf(
                message(ModelMessageRole.User, "hello"),
                message(ModelMessageRole.Assistant, "hi"),
                message(ModelMessageRole.User, "how are you"),
            ),
        )

        assertFalse(selection.truncated)
        assertFalse(selection.summaryInserted)
        assertEquals(3, selection.messageHistory.size)
    }

    @Test
    fun `long history truncation is deterministic`() {
        val manager = ContextWindowManager(promptBudgetUnits = 96)
        val history = listOf(
            message(ModelMessageRole.User, "oldest user message with plenty of characters"),
            message(ModelMessageRole.Assistant, "oldest assistant response with plenty of characters"),
            message(ModelMessageRole.User, "middle user message with plenty of characters"),
            message(ModelMessageRole.Assistant, "middle assistant response with plenty of characters"),
            message(ModelMessageRole.User, "latest user message with plenty of characters"),
        )

        val first = manager.select(systemPrompt = "short prompt", persistedHistory = history)
        val second = manager.select(systemPrompt = "short prompt", persistedHistory = history)

        assertTrue(first.truncated)
        assertEquals(first.messageHistory, second.messageHistory)
        assertTrue(first.messageHistory.any { it.content.contains("latest user message") })
        assertTrue(first.messageHistory.none { it.content.contains("oldest user message") })
    }

    @Test
    fun `tool call closure is preserved when a tool result is selected`() {
        val manager = ContextWindowManager(promptBudgetUnits = 120)
        val history = listOf(
            message(ModelMessageRole.User, "start"),
            message(
                role = ModelMessageRole.Assistant,
                content = "",
                toolCalls = listOf(
                    ProviderToolCall(
                        id = "call-1",
                        name = "health.status",
                        argumentsJson = buildJsonObject {
                            put("scope", "summary")
                        },
                    ),
                ),
            ),
            message(
                role = ModelMessageRole.Tool,
                content = "Health ok",
                toolCallId = "call-1",
            ),
            message(ModelMessageRole.User, "latest user"),
        )

        val selection = manager.select(systemPrompt = "short prompt", persistedHistory = history)
        val selectedToolRoles = selection.messageHistory.filter {
            it.toolCallId == "call-1" || it.toolCalls.any { toolCall -> toolCall.id == "call-1" }
        }

        assertEquals(2, selectedToolRoles.size)
        assertTrue(selectedToolRoles.any { it.role == ModelMessageRole.Assistant })
        assertTrue(selectedToolRoles.any { it.role == ModelMessageRole.Tool })
    }

    @Test
    fun `summary is inserted when older history is dropped`() {
        val manager = ContextWindowManager(promptBudgetUnits = 67)
        val history = listOf(
            message(ModelMessageRole.User, "very old user message with enough characters to be dropped"),
            message(ModelMessageRole.Assistant, "very old assistant message with enough characters to be dropped"),
            message(ModelMessageRole.User, "latest question"),
        )

        val selection = manager.select(
            systemPrompt = "short prompt",
            persistedHistory = history,
            summaryText = "Older turns discussed setup.",
        )

        assertTrue(selection.truncated)
        assertTrue(selection.summaryInserted)
        assertEquals(ModelMessageRole.System, selection.messageHistory.first().role)
        assertTrue(selection.messageHistory.first().content.contains("Session summary:"))
    }

    @Test
    fun `latest user message is never dropped even under a tiny budget`() {
        val manager = ContextWindowManager(promptBudgetUnits = 32)
        val history = listOf(
            message(ModelMessageRole.User, "older user message"),
            message(ModelMessageRole.Assistant, "older assistant message"),
            message(ModelMessageRole.User, "latest user message must stay"),
        )

        val selection = manager.select(systemPrompt = "prompt", persistedHistory = history)

        assertTrue(selection.messageHistory.any { it.role == ModelMessageRole.User && it.content == "latest user message must stay" })
    }

    @Test
    fun `larger system prompts leave less room for message history`() {
        val manager = ContextWindowManager(promptBudgetUnits = 120)
        val history = listOf(
            message(ModelMessageRole.User, "message one with some length"),
            message(ModelMessageRole.Assistant, "message two with some length"),
            message(ModelMessageRole.User, "message three with some length"),
            message(ModelMessageRole.Assistant, "message four with some length"),
        )

        val shortPrompt = manager.select(systemPrompt = "short", persistedHistory = history)
        val longPrompt = manager.select(systemPrompt = "long ".repeat(60), persistedHistory = history)

        assertTrue(longPrompt.diagnostics.availableMessageUnits < shortPrompt.diagnostics.availableMessageUnits)
        assertTrue(longPrompt.messageHistory.size <= shortPrompt.messageHistory.size)
    }

    private fun message(
        role: ModelMessageRole,
        content: String,
        toolCallId: String? = null,
        toolCalls: List<ProviderToolCall> = emptyList(),
    ): ModelMessage {
        return ModelMessage(
            role = role,
            content = content,
            toolCallId = toolCallId,
            toolCalls = toolCalls,
        )
    }
}
