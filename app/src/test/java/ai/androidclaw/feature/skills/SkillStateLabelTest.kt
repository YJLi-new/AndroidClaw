package ai.androidclaw.feature.skills

import ai.androidclaw.R
import ai.androidclaw.runtime.skills.SkillEligibilityStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class SkillStateLabelTest {
    @Test
    fun `disabled wins over every eligibility state`() {
        SkillEligibilityStatus.entries.forEach { status ->
            assertEquals(status.name, R.string.skills_state_disabled, skillStateLabelRes(enabled = false, status = status))
        }
    }

    @Test
    fun `enabled skills show their eligibility`() {
        assertEquals(R.string.skills_eligibility_ready, skillStateLabelRes(true, SkillEligibilityStatus.Eligible))
        assertEquals(R.string.skills_eligibility_invalid, skillStateLabelRes(true, SkillEligibilityStatus.Invalid))
        assertEquals(R.string.skills_eligibility_missing_tool, skillStateLabelRes(true, SkillEligibilityStatus.MissingTool))
        assertEquals(R.string.skills_eligibility_bridge_only, skillStateLabelRes(true, SkillEligibilityStatus.BridgeOnly))
    }
}
