package ai.androidclaw.runtime.skills

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillParserTest {
    private val parser = SkillParser()

    @Test
    fun `parses supported fields and preserves unknown fields`() {
        val raw =
            """
            ---
            name: list_tasks
            description: Direct tool dispatch
            user-invocable: true
            disable-model-invocation: true
            command-dispatch: tool
            command-tool: tasks.list
            metadata: '{"android":{"requiresTools":["tasks.list"]}}'
            unknown-field: keep-me
            ---
            
            Body content.
            """.trimIndent()

        val result = parser.parse(raw)
        assertTrue(result is SkillParseResult.Success)
        val document = (result as SkillParseResult.Success).document

        assertEquals("list_tasks", document.frontmatter.name)
        assertEquals(SkillCommandDispatch.Tool, document.frontmatter.commandDispatch)
        assertEquals("tasks.list", document.frontmatter.commandTool)
        assertEquals(
            "keep-me",
            document.frontmatter.unknownFields
                .getValue("unknown-field")
                .jsonPrimitive.content,
        )
        val metadata = document.frontmatter.metadata
        assertNotNull(metadata)
        assertEquals(
            "tasks.list",
            metadata!!
                .jsonObject
                .getValue("android")
                .jsonObject
                .getValue("requiresTools")
                .jsonArray[0]
                .jsonPrimitive
                .content,
        )
    }

    @Test
    fun `fails when required fields are missing`() {
        val raw =
            """
            ---
            description: Missing name
            ---
            """.trimIndent()

        val result = parser.parse(raw)
        assertTrue(result is SkillParseResult.Failure)
        assertEquals(
            "Skill frontmatter must contain non-empty name and description.",
            (result as SkillParseResult.Failure).error,
        )
    }
}
