package ai.androidclaw.data.db

import android.database.Cursor
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidClawDatabaseMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        requireNotNull(AndroidClawDatabase::class.java.canonicalName),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrate1To2_preservesLegacySkillRowsAndAddsNewColumns() {
        helper.createDatabase(SKILL_TEST_DB, 1).apply {
            execSQL(
                """
                INSERT INTO skill_records (
                    id,
                    sourceType,
                    enabled,
                    displayName,
                    description,
                    frontmatterJson,
                    eligibilityStatus,
                    eligibilityReasons,
                    importedAt,
                    updatedAt
                ) VALUES (
                    'bundled-summary',
                    'bundled',
                    1,
                    'Summary',
                    'Legacy bundled skill',
                    NULL,
                    'Eligible',
                    '[]',
                    NULL,
                    123456789
                )
                """.trimIndent(),
            )
            close()
        }

        helper.runMigrationsAndValidate(
            SKILL_TEST_DB,
            2,
            true,
            AndroidClawDatabaseMigrations.MIGRATION_1_2,
        ).query(
            """
            SELECT skillKey, workspaceSessionId, baseDir, instructionsMd, parseError
            FROM skill_records
            WHERE id = 'bundled-summary'
            """.trimIndent(),
        ).useCursor { cursor ->
            assertTrueMoveToFirst(cursor)
            assertEquals("Summary", cursor.getString(0))
            assertTrue(cursor.isNull(1))
            assertEquals("legacy://bundled/bundled-summary", cursor.getString(2))
            assertEquals("", cursor.getString(3))
            assertTrue(cursor.isNull(4))
        }
    }

    @Test
    fun migrate1To2_preservesSessionsTasksAndTaskRuns() {
        helper.createDatabase(RUNTIME_TEST_DB, 1).apply {
            execSQL(
                """
                INSERT INTO sessions (
                    id, title, isMain, createdAt, updatedAt, archivedAt, summaryText
                ) VALUES (
                    'main', 'Main session', 1, 1000, 1000, NULL, NULL
                )
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO tasks (
                    id, name, prompt, scheduleKind, scheduleSpec, executionMode,
                    targetSessionId, enabled, precise, nextRunAt, lastRunAt,
                    failureCount, maxRetries, createdAt, updatedAt
                ) VALUES (
                    'task-1', 'Daily status', 'Report status', 'once',
                    '{"at":"2026-03-10T00:00:00Z"}', 'MAIN_SESSION',
                    'main', 1, 0, 1700000000000, NULL,
                    0, 3, 1000, 1000
                )
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO task_runs (
                    id, taskId, status, scheduledAt, startedAt, finishedAt,
                    errorCode, errorMessage, resultSummary, outputMessageId
                ) VALUES (
                    'run-1', 'task-1', 'SUCCESS', 1700000000000, 1700000000100, 1700000000200,
                    NULL, NULL, 'Completed', NULL
                )
                """.trimIndent(),
            )
            close()
        }

        helper.runMigrationsAndValidate(
            RUNTIME_TEST_DB,
            2,
            true,
            AndroidClawDatabaseMigrations.MIGRATION_1_2,
        ).apply {
            query("SELECT name, targetSessionId, precise FROM tasks WHERE id = 'task-1'").useCursor { cursor ->
                assertTrueMoveToFirst(cursor)
                assertEquals("Daily status", cursor.getString(0))
                assertEquals("main", cursor.getString(1))
                assertEquals(0, cursor.getInt(2))
            }
            query("SELECT taskId, status, resultSummary FROM task_runs WHERE id = 'run-1'").useCursor { cursor ->
                assertTrueMoveToFirst(cursor)
                assertEquals("task-1", cursor.getString(0))
                assertEquals("SUCCESS", cursor.getString(1))
                assertEquals("Completed", cursor.getString(2))
            }
            close()
        }
    }

    private fun assertTrueMoveToFirst(cursor: Cursor) {
        check(cursor.moveToFirst()) { "Expected migrated row to exist." }
    }

    private fun <T> Cursor.useCursor(block: (Cursor) -> T): T {
        return use(block)
    }

    companion object {
        private const val SKILL_TEST_DB = "androidclaw-migration-skill-test"
        private const val RUNTIME_TEST_DB = "androidclaw-migration-runtime-test"
    }
}
