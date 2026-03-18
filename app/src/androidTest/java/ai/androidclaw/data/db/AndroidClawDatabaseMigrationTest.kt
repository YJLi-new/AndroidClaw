package ai.androidclaw.data.db

import android.database.Cursor
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidClawDatabaseMigrationTest {
    @get:Rule
    val helper =
        MigrationTestHelper(
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

        helper
            .runMigrationsAndValidate(
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
    fun migrate1To2_preservesMeaningfulRuntimeDataAcrossTables() {
        helper.createDatabase(RUNTIME_TEST_DB, 1).apply {
            execSQL(
                """
                INSERT INTO sessions (
                    id, title, isMain, createdAt, updatedAt, archivedAt, summaryText
                ) VALUES (
                    'main', 'Main session', 1, 1000, 1000, NULL, NULL
                ), (
                    'archive', 'Archive', 0, 2000, 2100, 2200, 'archived summary'
                )
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO messages (
                    id, sessionId, role, content, createdAt, providerMeta, toolCallId, taskRunId
                ) VALUES (
                    'msg-user', 'main', 'user', 'hello', 1100, NULL, NULL, NULL
                ), (
                    'msg-output', 'main', 'assistant', 'done', 1200, '{"provider":"fake"}', NULL, NULL
                ), (
                    'msg-archived', 'archive', 'system', 'archived note', 2300, NULL, 'call-1', 'run-failure'
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
                    'task-once', 'Daily status', 'Report status', 'once',
                    '{"at":"2026-03-10T00:00:00Z"}', 'MAIN_SESSION',
                    'main', 1, 1, 1700000000000, NULL,
                    0, 3, 1000, 1000
                ), (
                    'task-interval', 'Heartbeat', 'Ping', 'interval',
                    '{"everyMinutes":60,"anchorAt":"2026-03-09T01:00:00Z"}', 'ISOLATED_SESSION',
                    'archive', 1, 0, 1700003600000, 1700000000000,
                    1, 5, 1005, 1010
                ), (
                    'task-cron', 'Morning digest', 'Summarize', 'cron',
                    '{"expression":"0 9 * * *","timeZone":"UTC"}', 'MAIN_SESSION',
                    NULL, 0, 0, 1700007200000, NULL,
                    0, 2, 1015, 1020
                )
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO task_runs (
                    id, taskId, status, scheduledAt, startedAt, finishedAt,
                    errorCode, errorMessage, resultSummary, outputMessageId
                ) VALUES (
                    'run-success', 'task-once', 'SUCCESS', 1700000000000, 1700000000100, 1700000000200,
                    NULL, NULL, 'Completed', 'msg-output'
                ), (
                    'run-pending', 'task-interval', 'PENDING', 1700003600000, NULL, NULL,
                    NULL, NULL, NULL, NULL
                ), (
                    'run-failure', 'task-cron', 'FAILURE', 1700007200000, 1700007200100, 1700007200200,
                    'NETWORK', 'offline', 'Failed', NULL
                )
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO skill_records (
                    id, sourceType, enabled, displayName, description,
                    frontmatterJson, eligibilityStatus, eligibilityReasons, importedAt, updatedAt
                ) VALUES (
                    'bundled-summary', 'bundled', 1, 'Summary', 'Bundled skill',
                    NULL, 'Eligible', '[]', NULL, 123456789
                ), (
                    'local-helper', 'local', 0, 'Local Helper', 'Imported local skill',
                    '{"name":"Local Helper"}', 'Blocked', '["missing_env"]', 1700000000000, 1700000001000
                ), (
                    'workspace-ops', 'workspace', 1, 'Workspace Ops', 'Workspace skill',
                    '{"name":"Workspace Ops"}', 'Eligible', '[]', 1700000002000, 1700000003000
                )
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO event_logs (
                    id, timestamp, category, level, message, detailsJson
                ) VALUES (
                    'event-1', 1700000000000, 'scheduler', 'warn', 'Degraded', '{"reason":"exact_alarm_denied"}'
                ), (
                    'event-2', 1700000001000, 'system', 'info', 'Boot complete', NULL
                )
                """.trimIndent(),
            )
            close()
        }

        helper
            .runMigrationsAndValidate(
                RUNTIME_TEST_DB,
                2,
                true,
                AndroidClawDatabaseMigrations.MIGRATION_1_2,
            ).apply {
                assertRowCount(this, "sessions", 2)
                assertRowCount(this, "messages", 3)
                assertRowCount(this, "tasks", 3)
                assertRowCount(this, "task_runs", 3)
                assertRowCount(this, "skill_records", 3)
                assertRowCount(this, "event_logs", 2)

                query("SELECT archivedAt, summaryText FROM sessions WHERE id = 'archive'").useCursor { cursor ->
                    assertTrueMoveToFirst(cursor)
                    assertEquals(2200L, cursor.getLong(0))
                    assertEquals("archived summary", cursor.getString(1))
                }

                query(
                    """
                    SELECT sessionId, role, providerMeta, toolCallId, taskRunId
                    FROM messages
                    WHERE id = 'msg-archived'
                    """.trimIndent(),
                ).useCursor { cursor ->
                    assertTrueMoveToFirst(cursor)
                    assertEquals("archive", cursor.getString(0))
                    assertEquals("system", cursor.getString(1))
                    assertTrue(cursor.isNull(2))
                    assertEquals("call-1", cursor.getString(3))
                    assertEquals("run-failure", cursor.getString(4))
                }

                query(
                    """
                    SELECT name, scheduleKind, scheduleSpec, executionMode, targetSessionId, precise
                    FROM tasks
                    WHERE id = 'task-interval'
                    """.trimIndent(),
                ).useCursor { cursor ->
                    assertTrueMoveToFirst(cursor)
                    assertEquals("Heartbeat", cursor.getString(0))
                    assertEquals("interval", cursor.getString(1))
                    assertEquals("""{"everyMinutes":60,"anchorAt":"2026-03-09T01:00:00Z"}""", cursor.getString(2))
                    assertEquals("ISOLATED_SESSION", cursor.getString(3))
                    assertEquals("archive", cursor.getString(4))
                    assertEquals(0, cursor.getInt(5))
                }

                query(
                    """
                    SELECT taskId, status, startedAt, finishedAt, errorCode, errorMessage, resultSummary, outputMessageId
                    FROM task_runs
                    WHERE id = 'run-failure'
                    """.trimIndent(),
                ).useCursor { cursor ->
                    assertTrueMoveToFirst(cursor)
                    assertEquals("task-cron", cursor.getString(0))
                    assertEquals("FAILURE", cursor.getString(1))
                    assertEquals(1700007200100L, cursor.getLong(2))
                    assertEquals(1700007200200L, cursor.getLong(3))
                    assertEquals("NETWORK", cursor.getString(4))
                    assertEquals("offline", cursor.getString(5))
                    assertEquals("Failed", cursor.getString(6))
                    assertTrue(cursor.isNull(7))
                }

                assertMigratedSkill(
                    db = this,
                    id = "bundled-summary",
                    expectedSkillKey = "Summary",
                    expectedSourceType = "bundled",
                    expectedBaseDir = "legacy://bundled/bundled-summary",
                    expectedEnabled = 1,
                    expectedImportedAt = null,
                )
                assertMigratedSkill(
                    db = this,
                    id = "local-helper",
                    expectedSkillKey = "Local Helper",
                    expectedSourceType = "local",
                    expectedBaseDir = "legacy://local/local-helper",
                    expectedEnabled = 0,
                    expectedImportedAt = 1700000000000L,
                )
                assertMigratedSkill(
                    db = this,
                    id = "workspace-ops",
                    expectedSkillKey = "Workspace Ops",
                    expectedSourceType = "workspace",
                    expectedBaseDir = "legacy://workspace/workspace-ops",
                    expectedEnabled = 1,
                    expectedImportedAt = 1700000002000L,
                )

                query(
                    """
                    SELECT category, level, message, detailsJson
                    FROM event_logs
                    WHERE id = 'event-1'
                    """.trimIndent(),
                ).useCursor { cursor ->
                    assertTrueMoveToFirst(cursor)
                    assertEquals("scheduler", cursor.getString(0))
                    assertEquals("warn", cursor.getString(1))
                    assertEquals("Degraded", cursor.getString(2))
                    assertEquals("""{"reason":"exact_alarm_denied"}""", cursor.getString(3))
                }
                close()
            }
    }

    private fun assertMigratedSkill(
        db: androidx.sqlite.db.SupportSQLiteDatabase,
        id: String,
        expectedSkillKey: String,
        expectedSourceType: String,
        expectedBaseDir: String,
        expectedEnabled: Int,
        expectedImportedAt: Long?,
    ) {
        db
            .query(
                """
                SELECT skillKey, sourceType, workspaceSessionId, baseDir, enabled, instructionsMd, parseError, importedAt
                FROM skill_records
                WHERE id = ?
                """.trimIndent(),
                arrayOf(id),
            ).useCursor { cursor ->
                assertTrueMoveToFirst(cursor)
                assertEquals(expectedSkillKey, cursor.getString(0))
                assertEquals(expectedSourceType, cursor.getString(1))
                assertTrue(cursor.isNull(2))
                assertEquals(expectedBaseDir, cursor.getString(3))
                assertEquals(expectedEnabled, cursor.getInt(4))
                assertEquals("", cursor.getString(5))
                assertTrue(cursor.isNull(6))
                if (expectedImportedAt == null) {
                    assertTrue(cursor.isNull(7))
                } else {
                    assertEquals(expectedImportedAt, cursor.getLong(7))
                }
            }
    }

    private fun assertRowCount(
        db: androidx.sqlite.db.SupportSQLiteDatabase,
        tableName: String,
        expectedCount: Int,
    ) {
        db.query("SELECT COUNT(*) FROM $tableName").useCursor { cursor ->
            assertTrueMoveToFirst(cursor)
            assertEquals(expectedCount, cursor.getInt(0))
        }
    }

    private fun assertTrueMoveToFirst(cursor: Cursor) {
        check(cursor.moveToFirst()) { "Expected migrated row to exist." }
    }

    private fun <T> Cursor.useCursor(block: (Cursor) -> T): T = use(block)

    companion object {
        private const val SKILL_TEST_DB = "androidclaw-migration-skill-test"
        private const val RUNTIME_TEST_DB = "androidclaw-migration-runtime-test"
    }
}
