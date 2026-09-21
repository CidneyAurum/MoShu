package app.moshu.journal.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TodoDao {
    @Insert suspend fun insert(todo: TodoEntity): Long
    @Update suspend fun update(todo: TodoEntity)

    /**
     * 正式行动列表。必须排除 isAiSuggested：AI 提取的行动要等用户采纳才算数，
     * 否则详情页「采纳后才会进入行动」的说明与实际列表自相矛盾。
     */
    @Query("SELECT * FROM todos WHERE done = 0 AND isAiSuggested = 0 ORDER BY dueEpochDay IS NULL, dueEpochDay ASC, createdAt DESC")
    fun observeActive(): Flow<List<TodoEntity>>

    /** 尚未采纳的 AI 建议，单独成区。 */
    @Query("SELECT * FROM todos WHERE done = 0 AND isAiSuggested = 1 ORDER BY createdAt DESC")
    fun observeSuggested(): Flow<List<TodoEntity>>

    @Query("SELECT * FROM todos WHERE done = 1 ORDER BY completedAt DESC, id DESC")
    fun observeDone(): Flow<List<TodoEntity>>

    @Query("SELECT COUNT(*) FROM todos WHERE done = 0 AND isAiSuggested = 0") fun observeActiveCount(): Flow<Int>
    @Query("SELECT * FROM todos WHERE id = :id") suspend fun byId(id: Long): TodoEntity?
    @Query("SELECT * FROM todos WHERE uid = :uid LIMIT 1") suspend fun byUid(uid: String): TodoEntity?
    @Query("SELECT * FROM todos WHERE sourceEntryId = :sourceEntryId ORDER BY createdAt ASC") suspend fun bySource(sourceEntryId: Long): List<TodoEntity>
    @Query("SELECT * FROM todos WHERE sourceEntryId = :sourceEntryId ORDER BY done ASC, createdAt ASC") fun observeForEntry(sourceEntryId: Long): Flow<List<TodoEntity>>
    @Query("SELECT * FROM todos ORDER BY createdAt ASC") suspend fun allOnce(): List<TodoEntity>
    @Query("DELETE FROM todos WHERE sourceEntryId = :sourceEntryId AND userEdited = 0 AND isUserCreated = 0") suspend fun deleteUneditedBySource(sourceEntryId: Long)
    @Query("DELETE FROM todos WHERE id = :id") suspend fun deleteById(id: Long)
    @Query("DELETE FROM todos") suspend fun deleteAll()
}
