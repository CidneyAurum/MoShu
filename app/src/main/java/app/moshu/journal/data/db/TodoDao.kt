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

    @Query("SELECT * FROM todos WHERE done = 0 ORDER BY dueEpochDay IS NULL, dueEpochDay ASC, createdAt DESC")
    fun observeActive(): Flow<List<TodoEntity>>

    @Query("SELECT * FROM todos WHERE done = 1 ORDER BY completedAt DESC, id DESC")
    fun observeDone(): Flow<List<TodoEntity>>

    @Query("SELECT COUNT(*) FROM todos WHERE done = 0") fun observeActiveCount(): Flow<Int>
    @Query("SELECT * FROM todos WHERE id = :id") suspend fun byId(id: Long): TodoEntity?
    @Query("SELECT * FROM todos WHERE uid = :uid LIMIT 1") suspend fun byUid(uid: String): TodoEntity?
    @Query("SELECT * FROM todos WHERE sourceEntryId = :sourceEntryId ORDER BY createdAt ASC") suspend fun bySource(sourceEntryId: Long): List<TodoEntity>
    @Query("SELECT * FROM todos WHERE sourceEntryId = :sourceEntryId ORDER BY done ASC, createdAt ASC") fun observeForEntry(sourceEntryId: Long): Flow<List<TodoEntity>>
    @Query("SELECT * FROM todos ORDER BY createdAt ASC") suspend fun allOnce(): List<TodoEntity>
    @Query("DELETE FROM todos WHERE sourceEntryId = :sourceEntryId AND userEdited = 0 AND isUserCreated = 0") suspend fun deleteUneditedBySource(sourceEntryId: Long)
    @Query("DELETE FROM todos WHERE id = :id") suspend fun deleteById(id: Long)
    @Query("DELETE FROM todos") suspend fun deleteAll()
}
