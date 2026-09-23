package app.moshu.journal

import app.moshu.journal.data.db.EventEntity
import app.moshu.journal.data.db.RepeatRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 事件重复规则与提醒时刻。
 *
 * 这块最容易算错，而且错了用户很难发现：提醒晚一天、或者在 2 月跑到 3 月，
 * 用户只会觉得「提醒不准」，不会想到是重复规则的问题。
 */
class EventRepeatTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    private fun at(year: Int, month: Int, day: Int, hour: Int = 9, minute: Int = 0): Long =
        LocalDateTime.of(year, month, day, hour, minute).atZone(zone).toInstant().toEpochMilli()

    private fun event(
        startAt: Long,
        rule: String,
        reminderOffsetMin: Int = EventEntity.NO_REMINDER,
        done: Boolean = false,
    ) = EventEntity(title = "测试", startAt = startAt, repeatRule = rule, reminderOffsetMin = reminderOffsetMin, done = done)

    // ---------- 不重复 ----------

    @Test
    fun `不重复的事件没有下一次`() {
        val e = event(at(2026, 3, 1), RepeatRule.NONE)
        assertNull(RepeatRule.nextAfter(e, at(2026, 3, 2), zone))
    }

    @Test
    fun `还没到的事件下一次就是它自己`() {
        val start = at(2026, 3, 10)
        val e = event(start, RepeatRule.DAILY)
        assertEquals(start, RepeatRule.nextAfter(e, at(2026, 3, 1), zone))
    }

    // ---------- 每天 / 每周 ----------

    @Test
    fun `每天重复取次日同一时刻`() {
        val e = event(at(2026, 3, 1, 8, 30), RepeatRule.DAILY)
        val next = RepeatRule.nextAfter(e, at(2026, 3, 1, 20, 0), zone)!!
        assertEquals(LocalDateTime.of(2026, 3, 2, 8, 30), LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(next), zone))
    }

    @Test
    fun `每周重复保持星期几`() {
        // 2026-03-01 是周日；一周后仍是周日。
        val e = event(at(2026, 3, 1), RepeatRule.WEEKLY)
        val next = RepeatRule.nextAfter(e, at(2026, 3, 2), zone)!!
        assertEquals(LocalDate.of(2026, 3, 8), LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(next), zone).toLocalDate())
    }

    // ---------- 每月：月末夹取 ----------

    @Test
    fun `每月31号在2月落到当月最后一天而不是顺延到3月`() {
        // 用「加 30 天」或 plusMonths 不夹取都会跑到 3 月 3 日/2 月 28 日之后，
        // 用户心里那件事属于 2 月，不该跑到下个月。
        val e = event(at(2026, 1, 31), RepeatRule.MONTHLY)
        val next = RepeatRule.nextAfter(e, at(2026, 2, 1), zone)!!
        val day = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(next), zone)
        assertEquals(2, day.monthValue)
        assertEquals(28, day.dayOfMonth) // 2026 不是闰年
    }

    @Test
    fun `每月31号在闰年2月落到29号`() {
        val e = event(at(2028, 1, 31), RepeatRule.MONTHLY)
        val next = RepeatRule.nextAfter(e, at(2028, 2, 1), zone)!!
        val day = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(next), zone)
        assertEquals(2, day.monthValue)
        assertEquals(29, day.dayOfMonth)
    }

    @Test
    fun `每月30号在31天的月份保持30号`() {
        val e = event(at(2026, 3, 30), RepeatRule.MONTHLY)
        val next = RepeatRule.nextAfter(e, at(2026, 4, 1), zone)!!
        val day = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(next), zone)
        assertEquals(4, day.monthValue)
        assertEquals(30, day.dayOfMonth)
    }

    // ---------- 每年：2 月 29 日 ----------

    @Test
    fun `每年2月29日在平年落到2月28日`() {
        // 生日/纪念日设在 2 月 29 日，平年不能在 3 月才提醒。
        val e = event(at(2028, 2, 29), RepeatRule.YEARLY)
        val next = RepeatRule.nextAfter(e, at(2029, 1, 1), zone)!!
        val day = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(next), zone)
        assertEquals(2, day.monthValue)
        assertEquals(28, day.dayOfMonth)
    }

    // ---------- 跨年 ----------

    @Test
    fun `每天重复能跨年推进`() {
        val e = event(at(2026, 12, 31, 22, 0), RepeatRule.DAILY)
        val next = RepeatRule.nextAfter(e, at(2026, 12, 31, 23, 0), zone)!!
        assertEquals(
            LocalDateTime.of(2027, 1, 1, 22, 0),
            LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(next), zone),
        )
    }

    // ---------- 提醒时刻 ----------

    @Test
    fun `提醒时刻是发生时间减去提前量`() {
        val e = event(at(2026, 5, 1, 14, 30), RepeatRule.NONE, reminderOffsetMin = 15)
        val remind = e.remindAt!!
        assertEquals(
            LocalDateTime.of(2026, 5, 1, 14, 15),
            LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(remind), zone),
        )
    }

    @Test
    fun `不提醒时没有提醒时刻`() {
        assertNull(event(at(2026, 5, 1), RepeatRule.NONE).remindAt)
        assertTrue(!event(at(2026, 5, 1), RepeatRule.NONE).hasReminder)
    }

    @Test
    fun `准点提醒的偏移为零`() {
        val e = event(at(2026, 5, 1, 14, 30), RepeatRule.NONE, reminderOffsetMin = 0)
        assertEquals(e.startAt, e.remindAt)
        assertTrue(e.hasReminder)
    }

    // ---------- 排期 ----------

    @Test
    fun `已过期的提醒不再排期`() {
        // 设一个昨天的提醒不应被当成「立即触发」——否则用户刚保存就被弹通知。
        val e = event(at(2026, 5, 1, 9, 0), RepeatRule.NONE, reminderOffsetMin = 0)
        val next = app.moshu.journal.reminder.EventReminderScheduler.nextTriggerAt(e, at(2026, 5, 2), zone)
        assertNull(next)
    }

    @Test
    fun `已完成的事件不再排期`() {
        val e = event(at(2026, 5, 1, 9, 0), RepeatRule.DAILY, reminderOffsetMin = 0, done = true)
        assertNull(app.moshu.journal.reminder.EventReminderScheduler.nextTriggerAt(e, at(2026, 4, 30), zone))
    }

    @Test
    fun `重复事件的提醒按下一次发生计算`() {
        // 每天 9:00 的事件，在 5 月 1 日 10:00 看，下一次提醒应是 5 月 2 日 9:00。
        val e = event(at(2026, 5, 1, 9, 0), RepeatRule.DAILY, reminderOffsetMin = 0)
        val next = app.moshu.journal.reminder.EventReminderScheduler.nextTriggerAt(e, at(2026, 5, 1, 10, 0), zone)!!
        assertEquals(
            LocalDateTime.of(2026, 5, 2, 9, 0),
            LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(next), zone),
        )
    }

    @Test
    fun `提前一天提醒能在前一天触发`() {
        val e = event(at(2026, 5, 10, 9, 0), RepeatRule.NONE, reminderOffsetMin = 1440)
        val next = app.moshu.journal.reminder.EventReminderScheduler.nextTriggerAt(e, at(2026, 5, 1), zone)!!
        assertEquals(
            LocalDateTime.of(2026, 5, 9, 9, 0),
            LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(next), zone),
        )
    }
}
