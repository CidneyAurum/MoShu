package app.moshu.journal

import app.moshu.journal.reminder.ReminderScheduler
import org.junit.Assert.assertTrue
import org.junit.Test

class ReminderSchedulerTest {
    @Test
    fun `下一次提醒始终在未来一天内`() {
        val before = System.currentTimeMillis()
        val trigger = ReminderScheduler.nextTriggerTime(21, 30)
        assertTrue(trigger > before)
        assertTrue(trigger - before <= 24L * 60 * 60 * 1000 + 60_000)
    }
}
