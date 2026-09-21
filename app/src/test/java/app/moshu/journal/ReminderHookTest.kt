package app.moshu.journal

import app.moshu.journal.reminder.ReminderWorker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 提醒钩子的取值约束：30 字上限、必须像一句提问，不依赖网络。 */
class ReminderHookTest {

    @Test
    fun `钩子校验拒绝过短过长与无问句`() {
        assertTrue(ReminderWorker.isValidHook("今天还没留下一笔，想到什么了？"))
        assertFalse(ReminderWorker.isValidHook(""))
        assertFalse(ReminderWorker.isValidHook("短"))
        assertTrue(ReminderWorker.isValidHook("一".repeat(29) + "？"))
        assertFalse(ReminderWorker.isValidHook("一".repeat(30) + "？"))
        assertFalse(ReminderWorker.isValidHook("今天过得怎么样呢"))
    }

    @Test
    fun `钩子去掉各种包裹引号`() {
        assertEquals("今天过得怎么样？", ReminderWorker.sanitizeHook("\"「今天过得怎么样？」\""))
        assertEquals("今天过得怎么样？", ReminderWorker.sanitizeHook("  “今天过得怎么样？” "))
    }
}