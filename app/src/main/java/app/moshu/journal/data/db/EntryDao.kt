package app.moshu.journal.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface EntryDao {
    @Insert suspend fun insert(entry: EntryEntity): Long
    @Update suspend fun update(entry: EntryEntity)

    @Query("SELECT * FROM entries ORDER BY isPinned DESC, createdAt DESC")
    fun observeAll(): Flow<List<EntryEntity>>

    @Query("SELECT * FROM entries WHERE createdAt >= :since ORDER BY isPinned DESC, createdAt DESC")
    fun observeToday(since: Long): Flow<List<EntryEntity>>

    @Query("SELECT * FROM entries WHERE categoryId = :category ORDER BY isPinned DESC, createdAt DESC")
    fun observeByCategory(category: Int): Flow<List<EntryEntity>>

    @Query("SELECT entries.* FROM entries JOIN entries_fts ON entries.id = entries_fts.rowid WHERE entries_fts MATCH :ftsQuery ORDER BY entries.isPinned DESC, entries.createdAt DESC")
    fun searchFts(ftsQuery: String): Flow<List<EntryEntity>>

    /**
     * 子串匹配。FTS4 的默认分词器会把一整句中文当成单个 token，句中词用 MATCH 查不到，
     * 所以中日韩查询走这条路径。`!` 为转义符，避免用户输入的 % 和 _ 被当成通配符。
     */
    @Query("SELECT * FROM entries WHERE content LIKE '%' || :q || '%' ESCAPE '!' OR summary LIKE '%' || :q || '%' ESCAPE '!' OR tagsJson LIKE '%' || :q || '%' ESCAPE '!' ORDER BY isPinned DESC, createdAt DESC")
    fun searchLike(q: String): Flow<List<EntryEntity>>

    @Query("SELECT * FROM entries WHERE content LIKE '%' || :q || '%' ESCAPE '!' OR summary LIKE '%' || :q || '%' ESCAPE '!' OR tagsJson LIKE '%' || :q || '%' ESCAPE '!' ORDER BY createdAt DESC LIMIT :limit")
    suspend fun searchOnce(q: String, limit: Int = 20): List<EntryEntity>

    @Query("SELECT * FROM entries WHERE id = :id") suspend fun byId(id: Long): EntryEntity?
    @Query("SELECT * FROM entries WHERE id = :id") fun observeById(id: Long): Flow<EntryEntity?>
    @Query("SELECT * FROM entries WHERE createdAt >= :since AND createdAt < :until ORDER BY isPinned DESC, createdAt DESC")
    fun observeBetween(since: Long, until: Long): Flow<List<EntryEntity>>

    @Query("SELECT * FROM entries WHERE createdAt >= :since ORDER BY createdAt DESC") suspend fun since(since: Long): List<EntryEntity>
    @Query("SELECT * FROM entries WHERE createdAt >= :since AND createdAt < :until ORDER BY createdAt DESC") suspend fun between(since: Long, until: Long): List<EntryEntity>
    @Query("SELECT * FROM entries ORDER BY createdAt ASC") suspend fun allOnce(): List<EntryEntity>
    @Query("SELECT COUNT(*) FROM entries WHERE aiState IN ('pending', 'running')") fun observePendingCount(): Flow<Int>

    @Query("UPDATE entries SET aiState = :state, aiError = :error, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateAiState(id: Long, state: String, error: String = "", updatedAt: Long = System.currentTimeMillis())

    @Query("DELETE FROM entries WHERE id = :id") suspend fun deleteById(id: Long)
    @Query("DELETE FROM entries") suspend fun deleteAll()
}
