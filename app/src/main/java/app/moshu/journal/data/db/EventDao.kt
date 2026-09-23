package app.moshu.journal.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface EventDao {
    @Insert suspend fun insert(event: EventEntity): Long
    @Update suspend fun update(event: EventEntity)
    @Query("DELETE FROM events WHERE id = :id") suspend fun deleteById(id: Long)
    @Query("DELETE FROM events") suspend fun deleteAll()

    @Query("SELECT * FROM events WHERE id = :id") suspend fun byId(id: Long): EventEntity?
    @Query("SELECT * FROM events WHERE uid = :uid LIMIT 1") suspend fun byUid(uid: String): EventEntity?
    @Query("SELECT * FROM events ORDER BY startAt ASC") suspend fun allOnce(): List<EventEntity>

    /**
     * 某一天的事件（本地时区）。
     *
     * 时区偏移由调用方算好传入，而不是在 SQL 里做日期换算：
     * SQLite 的 date() 默认按 UTC，直接用它会把「凌晨的事件」分到前一天。
     */
    @Query("SELECT * FROM events WHERE startAt >= :dayStart AND startAt < :dayEnd ORDER BY startAt ASC")
    fun observeBetween(dayStart: Long, dayEnd: Long): Flow<List<EventEntity>>

    @Query("SELECT * FROM events WHERE startAt >= :dayStart AND startAt < :dayEnd ORDER BY startAt ASC")
    suspend fun between(dayStart: Long, dayEnd: Long): List<EventEntity>

    /** 整月的事件，供日历一次性取用（避免逐天查询）。 */
    @Query("SELECT * FROM events WHERE startAt >= :from AND startAt < :to ORDER BY startAt ASC")
    fun observeRange(from: Long, to: Long): Flow<List<EventEntity>>

    /** 还没到点、且开了提醒的事件——启动或时区变化后据此重排闹钟。 */
    @Query("SELECT * FROM events WHERE reminderOffsetMin >= 0 AND done = 0 AND startAt >= :now ORDER BY startAt ASC")
    suspend fun pendingReminders(now: Long): List<EventEntity>

    /** 首页要显示的「接下来」：从今天起最近若干条。 */
    @Query("SELECT * FROM events WHERE startAt >= :from AND done = 0 ORDER BY startAt ASC LIMIT :limit")
    fun observeUpcoming(from: Long, limit: Int): Flow<List<EventEntity>>

    @Query("SELECT COUNT(*) FROM events WHERE startAt >= :dayStart AND startAt < :dayEnd")
    fun observeDayCount(dayStart: Long, dayEnd: Long): Flow<Int>
}
