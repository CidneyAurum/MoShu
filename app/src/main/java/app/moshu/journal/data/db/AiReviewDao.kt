package app.moshu.journal.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AiReviewDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(review: AiReviewEntity)
    @Query("SELECT * FROM ai_reviews WHERE periodKey = :periodKey LIMIT 1") fun observe(periodKey: String): Flow<AiReviewEntity?>
    @Query("SELECT * FROM ai_reviews ORDER BY generatedAt DESC") suspend fun allOnce(): List<AiReviewEntity>
    @Query("DELETE FROM ai_reviews") suspend fun deleteAll()
}
