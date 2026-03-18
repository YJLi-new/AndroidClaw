package ai.androidclaw.runtime.orchestrator

import ai.androidclaw.data.model.ChatMessage
import ai.androidclaw.data.model.MessageRole
import ai.androidclaw.runtime.providers.ModelMessageRole
import ai.androidclaw.runtime.providers.ModelRunMode
import ai.androidclaw.runtime.skills.SkillCommandDispatch
import ai.androidclaw.runtime.skills.SkillEligibility
import ai.androidclaw.runtime.skills.SkillEligibilityStatus
import ai.androidclaw.runtime.skills.SkillFrontmatter
import ai.androidclaw.runtime.skills.SkillSnapshot
import ai.androidclaw.runtime.skills.SkillSourceType
import ai.androidclaw.runtime.tools.ToolArgumentSpec
import ai.androidclaw.runtime.tools.ToolDescriptor
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class PromptAssemblerTest {
    private val assembler = PromptAssembler()

    @Test
    fun `assemble includes run mode selected skills and tools in system prompt`() {
        val assembly =
            assembler.assemble(
                persistedMessages = emptyList(),
                selectedSkills = listOf(sampleSkill()),
                toolDescriptors =
                    listOf(
                        ToolDescriptor(
                            name = "health.status",
                            description = "Report health state",
                            aliases = listOf("health"),
                            arguments =
                                listOf(
                                    ToolArgumentSpec(
                                        name = "scope",
                                        required = true,
                                        description = "Requested scope",
                                    ),
                                ),
                        ),
                    ),
                runMode = ModelRunMode.Scheduled,
            )

        assertTrue(assembly.systemPrompt.contains("Run mode: scheduled."))
        assertTrue(assembly.systemPrompt.contains("demo_skill"))
        assertTrue(assembly.systemPrompt.contains("Follow the demo instructions."))
        assertTrue(assembly.systemPrompt.contains("health.status: Report health state"))
        assertTrue(assembly.systemPrompt.contains("[aliases: health]"))
    }

    @Test
    fun `assemble preserves persisted tool transcript structure for provider history`() {
        val assembly =
            assembler.assemble(
                persistedMessages =
                    listOf(
                        message(role = MessageRole.User, content = "hello"),
                        message(role = MessageRole.Assistant, content = "reply"),
                        message(role = MessageRole.System, content = "system note"),
                        message(
                            role = MessageRole.ToolCall,
                            content = """Tool request: health.status {"scope":"summary"}""",
                            toolCallId = "call-1",
                        ),
                        message(
                            role = MessageRole.ToolResult,
                            content = "Tool result: Health ok",
                            toolCallId = "call-1",
                        ),
                    ),
                selectedSkills = emptyList(),
                toolDescriptors = emptyList(),
                runMode = ModelRunMode.Interactive,
            )

        val history = assembly.messageHistory

        assertEquals(
            listOf(
                ModelMessageRole.User,
                ModelMessageRole.Assistant,
                ModelMessageRole.System,
                ModelMessageRole.Assistant,
                ModelMessageRole.Tool,
            ),
            history.map { it.role },
        )
        assertEquals("", history[3].content)
        assertEquals("health.status", history[3].toolCalls.single().name)
        assertEquals(
            "summary",
            history[3]
                .toolCalls
                .single()
                .argumentsJson
                .getValue("scope")
                .jsonPrimitive.content,
        )
        assertEquals("call-1", history[4].toolCallId)
        assertEquals("Health ok", history[4].content)
    }

    @Test
    fun `tool call messages that are not parseable json fall back to assistant text`() {
        val assembly =
            assembler.assemble(
                persistedMessages =
                    listOf(
                        message(
                            role = MessageRole.ToolCall,
                            content = "Tool request: tasks.list pending",
                            toolCallId = "call-raw",
                        ),
                    ),
                selectedSkills = emptyList(),
                toolDescriptors = emptyList(),
                runMode = ModelRunMode.Interactive,
            )

        assertEquals(1, assembly.messageHistory.size)
        assertEquals(ModelMessageRole.Assistant, assembly.messageHistory.single().role)
        assertEquals("Tool request: tasks.list pending", assembly.messageHistory.single().content)
        assertTrue(
            assembly.messageHistory
                .single()
                .toolCalls
                .isEmpty(),
        )
    }

    private fun sampleSkill(): SkillSnapshot =
        SkillSnapshot(
            id = "skill-1",
            skillKey = "demo_skill",
            sourceType = SkillSourceType.Bundled,
            baseDir = "asset://skills/skill-1",
            enabled = true,
            frontmatter =
                SkillFrontmatter(
                    name = "demo_skill",
                    description = "Demo skill",
                    homepage = null,
                    userInvocable = true,
                    disableModelInvocation = false,
                    commandDispatch = SkillCommandDispatch.Model,
                    commandTool = null,
                    commandArgMode = "raw",
                    metadata =
                        buildJsonObject {
                            put("enabled", JsonPrimitive(true))
                        },
                    unknownFields = emptyMap(),
                ),
            instructionsMd = "Follow the demo instructions.",
            eligibility = SkillEligibility(SkillEligibilityStatus.Eligible),
        )

    private fun message(
        role: MessageRole,
        content: String,
        toolCallId: String? = null,
    ): ChatMessage =
        ChatMessage(
            id = "message-$role-$toolCallId-$content",
            sessionId = "session-1",
            role = role,
            content = content,
            createdAt = Instant.parse("2026-03-09T00:00:00Z"),
            providerMeta = null,
            toolCallId = toolCallId,
            taskRunId = null,
        )
}
