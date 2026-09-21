package app.moshu.journal.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AttachmentDao {
    @Insert suspend fun insert(attachment: AttachmentEntity): Long
    @Query("SELECT * FROM attachments WHERE entryId = :entryId ORDER BY sortOrder, id") fun observeForEntry(entryId: Long): Flow<List<AttachmentEntity>>
    /** 只查当前列表可见条目的附件。调用方必须保证 ids 非空（Room 会生成非法的 IN ()）。 */
    @Query("SELECT * FROM attachments WHERE entryId IN (:entryIds) ORDER BY entryId, sortOrder") fun observeForEntries(entryIds: List<Long>): Flow<List<AttachmentEntity>>
    @Query("SELECT * FROM attachments WHERE entryId = :entryId ORDER BY sortOrder, id") suspend fun forEntry(entryId: Long): List<AttachmentEntity>
    @Query("SELECT * FROM attachments ORDER BY entryId, sortOrder") suspend fun allOnce(): List<AttachmentEntity>
    @Query("SELECT * FROM attachments ORDER BY entryId, sortOrder") fun observeAll(): Flow<List<AttachmentEntity>>
    @Query("SELECT * FROM attachments WHERE uid = :uid LIMIT 1") suspend fun byUid(uid: String): AttachmentEntity?
    @Query("DELETE FROM attachments WHERE id = :id") suspend fun deleteById(id: Long)
    @Query("DELETE FROM attachments") suspend fun deleteAll()
}
