package ai.androidclaw.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object AndroidClawDatabaseMigrations {
    val MIGRATION_1_2: Migration =
        object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `skill_records_new` (
                        `id` TEXT NOT NULL,
                        `skillKey` TEXT NOT NULL,
                        `sourceType` TEXT NOT NULL,
                        `workspaceSessionId` TEXT,
                        `baseDir` TEXT NOT NULL,
                        `enabled` INTEGER NOT NULL,
                        `displayName` TEXT NOT NULL,
                        `description` TEXT NOT NULL,
                        `frontmatterJson` TEXT,
                        `instructionsMd` TEXT NOT NULL,
                        `eligibilityStatus` TEXT NOT NULL,
                        `eligibilityReasons` TEXT NOT NULL,
                        `parseError` TEXT,
                        `importedAt` INTEGER,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT INTO `skill_records_new` (
                        `id`,
                        `skillKey`,
                        `sourceType`,
                        `workspaceSessionId`,
                        `baseDir`,
                        `enabled`,
                        `displayName`,
                        `description`,
                        `frontmatterJson`,
                        `instructionsMd`,
                        `eligibilityStatus`,
                        `eligibilityReasons`,
                        `parseError`,
                        `importedAt`,
                        `updatedAt`
                    )
                    SELECT
                        `id`,
                        `displayName`,
                        `sourceType`,
                        NULL,
                        'legacy://' || `sourceType` || '/' || `id`,
                        `enabled`,
                        `displayName`,
                        `description`,
                        `frontmatterJson`,
                        '',
                        `eligibilityStatus`,
                        `eligibilityReasons`,
                        NULL,
                        `importedAt`,
                        `updatedAt`
                    FROM `skill_records`
                    """.trimIndent(),
                )
                db.execSQL("DROP TABLE `skill_records`")
                db.execSQL("ALTER TABLE `skill_records_new` RENAME TO `skill_records`")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_skill_records_sourceType` ON `skill_records` (`sourceType`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_skill_records_enabled` ON `skill_records` (`enabled`)",
                )
            }
        }

    val ALL: Array<Migration> =
        arrayOf(
            MIGRATION_1_2,
        )
}
