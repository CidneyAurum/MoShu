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

    // 以下读取查询一律带 `deletedAt = 0`：回收站里的条目不应出现在列表、搜索、
    // 统计或 AI 整理的范围里，否则「已删除」就是假的。

    @Query("SELECT * FROM entries WHERE deletedAt = 0 ORDER BY isPinned DESC, createdAt DESC")
    fun observeAll(): Flow<List<EntryEntity>>

    @Query("SELECT * FROM entries WHERE deletedAt = 0 AND createdAt >= :since ORDER BY isPinned DESC, createdAt DESC")
    fun observeToday(since: Long): Flow<List<EntryEntity>>

    @Query("SELECT * FROM entries WHERE deletedAt = 0 AND categoryId = :category ORDER BY isPinned DESC, createdAt DESC")
    fun observeByCategory(category: Int): Flow<List<EntryEntity>>

    @Query("SELECT * FROM entries WHERE deletedAt = 0 AND isStarred = 1 ORDER BY createdAt DESC")
    fun observeStarred(): Flow<List<EntryEntity>>

    @Query("SELECT entries.* FROM entries JOIN entries_fts ON entries.id = entries_fts.rowid WHERE entries.deletedAt = 0 AND entries_fts MATCH :ftsQuery ORDER BY entries.isPinned DESC, entries.createdAt DESC")
    fun searchFts(ftsQuery: String): Flow<List<EntryEntity>>

    /**
     * 子串匹配。FTS4 的默认分词器会把一整句中文当成单个 token，句中词用 MATCH 查不到，
     * 所以中日韩查询走这条路径。`!` 为转义符，避免用户输入的 % 和 _ 被当成通配符。
     */
    @Query("SELECT * FROM entries WHERE deletedAt = 0 AND (content LIKE '%' || :q || '%' ESCAPE '!' OR summary LIKE '%' || :q || '%' ESCAPE '!' OR tagsJson LIKE '%' || :q || '%' ESCAPE '!') ORDER BY isPinned DESC, createdAt DESC")
    fun searchLike(q: String): Flow<List<EntryEntity>>

    @Query("SELECT * FROM entries WHERE deletedAt = 0 AND (content LIKE '%' || :q || '%' ESCAPE '!' OR summary LIKE '%' || :q || '%' ESCAPE '!' OR tagsJson LIKE '%' || :q || '%' ESCAPE '!') ORDER BY createdAt DESC LIMIT :limit")
    suspend fun searchOnce(q: String, limit: Int = 20): List<EntryEntity>

    /** 按标题精确匹配，供 `[[双向链接]]` 解析。标题取正文首行。 */
    @Query("SELECT * FROM entries WHERE deletedAt = 0 AND (content = :title OR content LIKE :title || char(10) || '%') ORDER BY createdAt DESC LIMIT 1")
    suspend fun byTitle(title: String): EntryEntity?

    /** 反链：正文里出现 `[[title]]` 的其它条目。 */
    @Query("SELECT * FROM entries WHERE deletedAt = 0 AND id != :excludeId AND content LIKE '%[[' || :title || ']]%' ORDER BY createdAt DESC LIMIT 50")
    suspend fun backlinks(title: String, excludeId: Long): List<EntryEntity>

    @Query("SELECT * FROM entries WHERE id = :id") suspend fun byId(id: Long): EntryEntity?
    @Query("SELECT * FROM entries WHERE id = :id") fun observeById(id: Long): Flow<EntryEntity?>
    @Query("SELECT * FROM entries WHERE deletedAt = 0 AND createdAt >= :since AND createdAt < :until ORDER BY isPinned DESC, createdAt DESC")
    fun observeBetween(since: Long, until: Long): Flow<List<EntryEntity>>

    @Query("SELECT * FROM entries WHERE deletedAt = 0 AND createdAt >= :since ORDER BY createdAt DESC") suspend fun since(since: Long): List<EntryEntity>
    @Query("SELECT * FROM entries WHERE deletedAt = 0 AND createdAt >= :since AND createdAt < :until ORDER BY createdAt DESC") suspend fun between(since: Long, until: Long): List<EntryEntity>
    @Query("SELECT * FROM entries WHERE deletedAt = 0 ORDER BY createdAt ASC") suspend fun allOnce(): List<EntryEntity>
    @Query("SELECT COUNT(*) FROM entries WHERE deletedAt = 0 AND aiState IN ('pending', 'running')") fun observePendingCount(): Flow<Int>

    // ---------- 回收站 ----------

    @Query("SELECT * FROM entries WHERE deletedAt > 0 ORDER BY deletedAt DESC")
    fun observeTrash(): Flow<List<EntryEntity>>

    @Query("SELECT * FROM entries WHERE deletedAt > 0 ORDER BY deletedAt DESC")
    suspend fun trashOnce(): List<EntryEntity>

    @Query("SELECT COUNT(*) FROM entries WHERE deletedAt > 0")
    fun observeTrashCount(): Flow<Int>

    /** 回收站保留期已过的条目 id，供启动时清理。 */
    @Query("SELECT id FROM entries WHERE deletedAt > 0 AND deletedAt < :before")
    suspend fun expiredTrashIds(before: Long): List<Long>

    @Query("UPDATE entries SET deletedAt = :at, updatedAt = :at WHERE id = :id")
    suspend fun markDeleted(id: Long, at: Long = System.currentTimeMillis())

    @Query("UPDATE entries SET deletedAt = 0, updatedAt = :at WHERE id = :id")
    suspend fun restoreFromTrash(id: Long, at: Long = System.currentTimeMillis())

    @Query("UPDATE entries SET isStarred = :starred, updatedAt = :at WHERE id = :id")
    suspend fun setStarred(id: Long, starred: Boolean, at: Long = System.currentTimeMillis())

    @Query("SELECT * FROM entries ORDER BY createdAt ASC") suspend fun allIncludingTrash(): List<EntryEntity>

    @Query("UPDATE entries SET aiState = :state, aiError = :error, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateAiState(id: Long, state: String, error: String = "", updatedAt: Long = System.currentTimeMillis())

    /** 供整理引擎统计已有标签词频，促使模型复用用户既有标签。 */
    @Query("SELECT tagsJson FROM entries WHERE deletedAt = 0 AND tagsJson != '[]' AND tagsJson != ''")
    suspend fun allTagsJson(): List<String>

    /** 整理失败的条目数，用于发一条汇总通知而不是每条一弹。 */
    @Query("SELECT COUNT(*) FROM entries WHERE deletedAt = 0 AND aiState = 'failed'")
    suspend fun countFailed(): Int

    /** 统计提示词/模型版本落后的条目数，供设置页提示「重新整理旧记录」。 */
    @Query("SELECT COUNT(*) FROM entries WHERE deletedAt = 0 AND aiPromptVersion < :promptVersion")
    suspend fun countOutdated(promptVersion: Int): Int

    @Query("DELETE FROM entries WHERE id = :id") suspend fun deleteById(id: Long)
    @Query("DELETE FROM entries") suspend fun deleteAll()
}
