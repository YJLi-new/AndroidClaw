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

    val MIGRATION_2_3: Migration =
        object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `sessions` ADD COLUMN `compactedUntilMessageId` TEXT")
            }
        }

    val MIGRATION_3_4: Migration =
        object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `memory_items` (
                        `id` TEXT NOT NULL,
                        `ownerUserId` TEXT NOT NULL,
                        `text` TEXT NOT NULL,
                        `sourceSessionId` TEXT,
                        `sourceMessageIdsJson` TEXT NOT NULL,
                        `sourceType` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        `deletedAt` INTEGER,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_memory_items_ownerUserId` ON `memory_items` (`ownerUserId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_memory_items_createdAt` ON `memory_items` (`createdAt`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_memory_items_deletedAt` ON `memory_items` (`deletedAt`)",
                )
            }
        }

    val MIGRATION_4_5: Migration =
        object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `message_search_tokens` (
                        `messageId` TEXT NOT NULL,
                        `sessionId` TEXT NOT NULL,
                        `token` TEXT NOT NULL,
                        PRIMARY KEY(`messageId`, `token`),
                        FOREIGN KEY(`messageId`) REFERENCES `messages`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_message_search_tokens_token` " +
                        "ON `message_search_tokens` (`token`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_message_search_tokens_sessionId_token` " +
                        "ON `message_search_tokens` (`sessionId`, `token`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_message_search_tokens_messageId` " +
                        "ON `message_search_tokens` (`messageId`)",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `memory_search_tokens` (
                        `memoryId` TEXT NOT NULL,
                        `ownerUserId` TEXT NOT NULL,
                        `token` TEXT NOT NULL,
                        PRIMARY KEY(`memoryId`, `token`),
                        FOREIGN KEY(`memoryId`) REFERENCES `memory_items`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_memory_search_tokens_token` " +
                        "ON `memory_search_tokens` (`token`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_memory_search_tokens_ownerUserId_token` " +
                        "ON `memory_search_tokens` (`ownerUserId`, `token`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_memory_search_tokens_memoryId` " +
                        "ON `memory_search_tokens` (`memoryId`)",
                )
            }
        }

    val ALL: Array<Migration> =
        arrayOf(
            MIGRATION_1_2,
            MIGRATION_2_3,
            MIGRATION_3_4,
            MIGRATION_4_5,
        )
}
