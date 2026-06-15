package ai.androidclaw.data

import ai.androidclaw.testutil.InMemorySkillConfigStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SkillConfigStoreTest {
    private lateinit var store: AndroidSkillConfigStore

    @Before
    fun setUp() {
        store = AndroidSkillConfigStore(ApplicationProvider.getApplicationContext())
    }

    @Test
    fun `android skill config store trims and bounds identifiers and values`() =
        runTest {
            val unique = UUID.randomUUID().toString()
            val skillKey = "skill-$unique-" + "s".repeat(SKILL_CONFIG_SKILL_KEY_MAX_CHARS + 20)
            val configPath = "config.$unique." + "p".repeat(SKILL_CONFIG_PATH_MAX_CHARS + 20)
            val value = "v".repeat(SKILL_CONFIG_VALUE_MAX_CHARS + 20)

            store.writeConfig(
                skillKey = "  $skillKey  ",
                configPath = "  $configPath  ",
                value = "  $value  ",
            )

            val expectedConfigPath = configPath.toBoundedSkillConfigIdentifier(SKILL_CONFIG_PATH_MAX_CHARS)!!
            val expectedValue = value.take(SKILL_CONFIG_VALUE_MAX_CHARS)

            assertEquals(expectedValue, store.readConfig(skillKey, configPath))
            assertEquals(mapOf(expectedConfigPath to expectedValue), store.readConfigs(skillKey))

            store.writeConfig(skillKey, configPath, null)

            assertNull(store.readConfig(skillKey, configPath))
            assertEquals(emptyMap<String, String>(), store.readConfigs(skillKey))
        }

    @Test
    fun `android skill config store ignores blank identifiers and blank values`() =
        runTest {
            val skillKey = "skill-${UUID.randomUUID()}"

            store.writeConfig(skillKey = "", configPath = "calendar.accountId", value = "primary")
            store.writeConfig(skillKey = skillKey, configPath = "   ", value = "primary")
            store.writeConfig(skillKey = skillKey, configPath = "calendar.accountId", value = "   ")

            assertNull(store.readConfig("", "calendar.accountId"))
            assertNull(store.readConfig(skillKey, ""))
            assertNull(store.readConfig(skillKey, "calendar.accountId"))
            assertEquals(emptyMap<String, String>(), store.readConfigs(""))
            assertEquals(emptyMap<String, String>(), store.readConfigs(skillKey))
        }

    @Test
    fun `in memory skill config store mirrors production bounds`() =
        runTest {
            val skillKey = "fixture-" + "s".repeat(SKILL_CONFIG_SKILL_KEY_MAX_CHARS + 20)
            val configPath = "fixture." + "p".repeat(SKILL_CONFIG_PATH_MAX_CHARS + 20)
            val value = "x".repeat(SKILL_CONFIG_VALUE_MAX_CHARS + 20)
            val store = InMemorySkillConfigStore()

            store.writeConfig(skillKey, configPath, "  $value  ")

            val expectedConfigPath = configPath.toBoundedSkillConfigIdentifier(SKILL_CONFIG_PATH_MAX_CHARS)!!
            val expectedValue = value.take(SKILL_CONFIG_VALUE_MAX_CHARS)

            assertEquals(expectedValue, store.readConfig(skillKey, configPath))
            assertEquals(mapOf(expectedConfigPath to expectedValue), store.readConfigs(skillKey))
        }
}
