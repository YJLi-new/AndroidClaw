package ai.androidclaw.data.db

import android.content.Context
import ai.androidclaw.data.db.dao.EventLogDao
import ai.androidclaw.data.db.dao.MessageDao
import ai.androidclaw.data.db.dao.SessionDao
import ai.androidclaw.data.db.dao.SkillRecordDao
import ai.androidclaw.data.db.dao.TaskDao
import ai.androidclaw.data.db.dao.TaskRunDao
import ai.androidclaw.data.db.entity.EventLogEntity
import ai.androidclaw.data.db.entity.MessageEntity
import ai.androidclaw.data.db.entity.SessionEntity
import ai.androidclaw.data.db.entity.SkillRecordEntity
import ai.androidclaw.data.db.entity.TaskEntity
import ai.androidclaw.data.db.entity.TaskRunEntity
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        SessionEntity::class,
        MessageEntity::class,
        TaskEntity::class,
        TaskRunEntity::class,
        SkillRecordEntity::class,
        EventLogEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class AndroidClawDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun messageDao(): MessageDao
    abstract fun taskDao(): TaskDao
    abstract fun taskRunDao(): TaskRunDao
    abstract fun skillRecordDao(): SkillRecordDao
    abstract fun eventLogDao(): EventLogDao

    companion object {
        fun build(context: Context): AndroidClawDatabase {
            return Room.databaseBuilder(context, AndroidClawDatabase::class.java, "androidclaw.db")
                .addMigrations(*AndroidClawDatabaseMigrations.ALL)
                .build()
        }
    }
}
