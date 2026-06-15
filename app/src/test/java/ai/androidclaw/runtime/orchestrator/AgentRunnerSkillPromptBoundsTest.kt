package ai.androidclaw.runtime.orchestrator

import ai.androidclaw.runtime.skills.SkillCommandDispatch
import ai.androidclaw.runtime.skills.SkillEligibility
import ai.androidclaw.runtime.skills.SkillEligibilityStatus
import ai.androidclaw.runtime.skills.SkillFrontmatter
import ai.androidclaw.runtime.skills.SkillSnapshot
import ai.androidclaw.runtime.skills.SkillSourceType
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRunnerSkillPromptBoundsTest {
    @Test
    fun `bounded model skill metadata caps count and text`() {
        val longId = "id" + "i".repeat(MAX_PROMPT_SKILL_NAME_CHARS) + "ID_TAIL"
        val longName = "n".repeat(MAX_PROMPT_SKILL_NAME_CHARS) + "NAME_TAIL"
        val longDescription = "d".repeat(MAX_PROMPT_SKILL_DESCRIPTION_CHARS) + "DESCRIPTION_TAIL"
        val longInstructions = "x".repeat(MAX_PROMPT_SKILL_INSTRUCTIONS_CHARS) + "INSTRUCTIONS_TAIL"

        val metadata =
            (1..(MAX_PROMPT_SKILLS + 1)).map { index ->
                sampleSkill(
                    id = if (index == 1) longId else "skill-$index",
                    name = if (index == 1) longName else "skill_$index",
                    description = if (index == 1) longDescription else "Description $index",
                    instructions = if (index == 1) longInstructions else "Instructions $index",
                )
            }.toBoundedModelSkillMetadata()

        assertEquals(MAX_PROMPT_SKILLS, metadata.size)
        assertEquals(longId.take(MAX_PROMPT_SKILL_NAME_CHARS), metadata.first().id)
        assertEquals(longName.take(MAX_PROMPT_SKILL_NAME_CHARS), metadata.first().name)
        assertEquals(longDescription.take(MAX_PROMPT_SKILL_DESCRIPTION_CHARS), metadata.first().description)
        assertEquals(longInstructions.take(MAX_PROMPT_SKILL_INSTRUCTIONS_CHARS), metadata.first().instructions)
        assertEquals("skill_$MAX_PROMPT_SKILLS", metadata.last().name)
        assertTrue(metadata.none { it.name == "skill_${MAX_PROMPT_SKILLS + 1}" })
        assertTrue(metadata.none { it.id.contains("ID_TAIL") })
        assertTrue(metadata.none { it.name.contains("NAME_TAIL") })
        assertTrue(metadata.none { it.description.contains("DESCRIPTION_TAIL") })
        assertTrue(metadata.none { it.instructions.contains("INSTRUCTIONS_TAIL") })
    }

    @Test
    fun `active skill suffix caps names and reports omitted skills`() {
        val longName = "n".repeat(MAX_PROMPT_SKILL_NAME_CHARS) + "NAME_TAIL"
        val text =
            "Assistant reply".withActiveSkills(
                (1..(MAX_PROMPT_SKILLS + 2)).map { index ->
                    sampleSkill(
                        id = "skill-$index",
                        name = if (index == 1) longName else "skill_$index",
                    )
                },
            )

        assertTrue(text.startsWith("Assistant reply"))
        assertTrue(text.contains("Active skills:"))
        assertTrue(text.contains(longName.take(MAX_PROMPT_SKILL_NAME_CHARS)))
        assertTrue(text.contains("skill_$MAX_PROMPT_SKILLS"))
        assertTrue(text.contains("+2 more"))
        assertTrue(!text.contains("NAME_TAIL"))
        assertTrue(!text.contains("skill_${MAX_PROMPT_SKILLS + 1}"))
        assertTrue(!text.contains("skill_${MAX_PROMPT_SKILLS + 2}"))
    }
}

private fun sampleSkill(
    id: String,
    name: String,
    description: String = "Demo skill",
    instructions: String = "Follow the demo instructions.",
): SkillSnapshot =
    SkillSnapshot(
        id = id,
        skillKey = name,
        sourceType = SkillSourceType.Bundled,
        baseDir = "asset://skills/$id",
        enabled = true,
        frontmatter =
            SkillFrontmatter(
                name = name,
                description = description,
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
        instructionsMd = instructions,
        eligibility = SkillEligibility(SkillEligibilityStatus.Eligible),
    )
