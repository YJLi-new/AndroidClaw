package ai.androidclaw.data

import ai.androidclaw.testutil.InMemorySkillSecretStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillSecretStoreTest {
    @Test
    fun `skill secret storage keys are delimiter safe and keep legacy fallback for safe ids`() {
        val first = skillSecretStorageKeyCandidates(skillKey = "a:b", envName = "c")
        val second = skillSecretStorageKeyCandidates(skillKey = "a", envName = "b:c")
        val safe = skillSecretStorageKeyCandidates(skillKey = "safe_skill", envName = "API_KEY")

        assertNotEquals(first.first(), second.first())
        assertTrue(first.first().startsWith("skill_secret_v2_"))
        assertEquals("skill_secret_safe_skill:API_KEY", safe[1])
    }

    @Test
    fun `skill secret identifiers and values are bounded`() =
        runTest {
            val skillKey = "skill-" + "s".repeat(SKILL_SECRET_SKILL_KEY_MAX_CHARS + 20)
            val envName = "ENV_" + "e".repeat(SKILL_SECRET_ENV_NAME_MAX_CHARS + 20)
            val value = "v".repeat(SKILL_SECRET_VALUE_MAX_CHARS + 20)
            val store = InMemorySkillSecretStore()

            store.writeSecret(
                skillKey = "  $skillKey  ",
                envName = "  $envName  ",
                value = "  $value  ",
            )

            assertEquals(value.take(SKILL_SECRET_VALUE_MAX_CHARS), store.readSecret(skillKey, envName))
        }

    @Test
    fun `skill secret store ignores blank identifiers and clears blank values`() =
        runTest {
            val store = InMemorySkillSecretStore()

            store.writeSecret(skillKey = "", envName = "API_KEY", value = "secret")
            store.writeSecret(skillKey = "skill", envName = "", value = "secret")
            store.writeSecret(skillKey = "skill", envName = "API_KEY", value = "secret")
            store.writeSecret(skillKey = "skill", envName = "API_KEY", value = "   ")

            assertNull(store.readSecret("", "API_KEY"))
            assertNull(store.readSecret("skill", ""))
            assertNull(store.readSecret("skill", "API_KEY"))
        }

    @Test
    fun `skill secret recovery notices use normalized identifiers`() =
        runTest {
            val skillKey = "skill-" + "s".repeat(SKILL_SECRET_SKILL_KEY_MAX_CHARS + 20)
            val envName = "ENV_" + "e".repeat(SKILL_SECRET_ENV_NAME_MAX_CHARS + 20)
            val store = InMemorySkillSecretStore()

            store.markRecoveryNotice("  $skillKey  ", "  $envName  ")

            assertEquals(true, store.consumeRecoveryNotice(skillKey, envName))
            assertEquals(false, store.consumeRecoveryNotice(skillKey, envName))
        }
}
