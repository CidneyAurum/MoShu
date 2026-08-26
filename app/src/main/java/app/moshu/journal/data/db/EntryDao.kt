package app.moshu.journal.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface EntryDao {
    @Insert suspend fun insert(entry: EntryEntity): Long
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertAll(entries: List<EntryEntity>): List<Long>
    @Update suspend fun update(entry: EntryEntity)

    @Query("SELECT * FROM entries ORDER BY isPinned DESC, createdAt DESC")
    fun observeAll(): Flow<List<EntryEntity>>

    @Query("SELECT * FROM entries WHERE createdAt >= :since ORDER BY isPinned DESC, createdAt DESC")
    fun observeToday(since: Long): Flow<List<EntryEntity>>

    @Query("SELECT * FROM entries WHERE categoryId = :category ORDER BY isPinned DESC, createdAt DESC")
    fun observeByCategory(category: Int): Flow<List<EntryEntity>>

    @Query("SELECT entries.* FROM entries JOIN entries_fts ON entries.id = entries_fts.rowid WHERE entries_fts MATCH :ftsQuery ORDER BY entries.isPinned DESC, entries.createdAt DESC")
    fun searchFts(ftsQuery: String): Flow<List<EntryEntity>>

    @Query("SELECT * FROM entries WHERE content LIKE '%' || :q || '%' OR summary LIKE '%' || :q || '%' OR tagsJson LIKE '%' || :q || '%' ORDER BY isPinned DESC, createdAt DESC")
    fun search(q: String): Flow<List<EntryEntity>>

    @Query("SELECT * FROM entries WHERE content LIKE '%' || :q || '%' OR summary LIKE '%' || :q || '%' OR tagsJson LIKE '%' || :q || '%' ORDER BY createdAt DESC LIMIT :limit")
    suspend fun searchOnce(q: String, limit: Int = 20): List<EntryEntity>

    @Query("SELECT * FROM entries WHERE id = :id") suspend fun byId(id: Long): EntryEntity?
    @Query("SELECT * FROM entries WHERE id = :id") fun observeById(id: Long): Flow<EntryEntity?>
    @Query("SELECT * FROM entries WHERE uid = :uid LIMIT 1") suspend fun byUid(uid: String): EntryEntity?
    @Query("SELECT * FROM entries WHERE createdAt >= :since ORDER BY createdAt DESC") suspend fun since(since: Long): List<EntryEntity>
    @Query("SELECT * FROM entries WHERE createdAt >= :since AND createdAt < :until ORDER BY createdAt DESC") suspend fun between(since: Long, until: Long): List<EntryEntity>
    @Query("SELECT * FROM entries WHERE createdAt >= :since ORDER BY createdAt DESC") fun observeSince(since: Long): Flow<List<EntryEntity>>
    @Query("SELECT * FROM entries ORDER BY createdAt ASC") suspend fun allOnce(): List<EntryEntity>
    @Query("SELECT COUNT(*) FROM entries WHERE aiState IN ('pending', 'running')") fun observePendingCount(): Flow<Int>

    @Query("UPDATE entries SET aiState = :state, aiError = :error, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateAiState(id: Long, state: String, error: String = "", updatedAt: Long = System.currentTimeMillis())

    @Query("DELETE FROM entries WHERE id = :id") suspend fun deleteById(id: Long)
    @Query("DELETE FROM entries") suspend fun deleteAll()
}
