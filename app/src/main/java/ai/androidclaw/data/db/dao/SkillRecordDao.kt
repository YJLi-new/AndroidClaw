package ai.androidclaw.data.db.dao

import ai.androidclaw.data.db.entity.SkillRecordEntity
import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface SkillRecordDao {
    @Upsert
    suspend fun upsert(record: SkillRecordEntity)

    @Upsert
    suspend fun upsertAll(records: List<SkillRecordEntity>)

    @Query("SELECT * FROM skill_records ORDER BY displayName ASC")
    fun getAll(): Flow<List<SkillRecordEntity>>

    @Query("SELECT * FROM skill_records WHERE enabled = 1 ORDER BY displayName ASC")
    suspend fun getEnabled(): List<SkillRecordEntity>

    @Query("SELECT * FROM skill_records WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): SkillRecordEntity?

    @Query("DELETE FROM skill_records WHERE id = :id")
    suspend fun delete(id: String)
}

