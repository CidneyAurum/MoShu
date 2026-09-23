package app.moshu.journal

import app.moshu.journal.data.db.EventEntity
import app.moshu.journal.data.db.RepeatRule
import app.moshu.journal.data.schedule.EventIntentParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * 自然语言安排解析。
 *
 * 日期算错是这类功能最致命的缺陷：用户说「下周五」，系统安排到本周五，
 * 结果错过事情——比不安排还糟。所以这里把边界（跨周、跨月、跨年、当天已过）全部钉住。
 *
 * 固定「现在」为 2026-09-23（周三）10:00，让断言可复现。
 */
class EventIntentParserTest {

    private val now = LocalDateTime.of(2026, 9, 23, 10, 0) // 周三

    private fun parse(text: String) = EventIntentParser.parse(text, now)

    // ---------- 星期几 ----------

    @Test
    fun `周五取本周即将到来的那个周五`() {
        val intent = parse("这周五八点去上实验课")
        assertEquals(LocalDate.of(2026, 9, 25), intent.day)
        assertEquals(LocalTime.of(8, 0), intent.time)
    }

    @Test
    fun `当天是周三时本周三取今天`() {
        // nextOrSame：说「周三」而今天就是周三，指的是今天，不是下周。
        val intent = parse("周三交周报")
        assertEquals(LocalDate.of(2026, 9, 23), intent.day)
    }

    @Test
    fun `已过去的星期几顺延到下周`() {
        // 今天周三，说「周一」应指下周一（9/28），而不是本周已过的 9/21。
        val intent = parse("周一开会")
        assertEquals(LocalDate.of(2026, 9, 28), intent.day)
        assertEquals(DayOfWeek.MONDAY, intent.day!!.dayOfWeek)
    }

    @Test
    fun `下周五是下周的周五`() {
        val intent = parse("下周五聚餐")
        assertEquals(LocalDate.of(2026, 10, 2), intent.day)
        assertEquals(DayOfWeek.FRIDAY, intent.day!!.dayOfWeek)
    }

    @Test
    fun `周天与周日都识别为周日`() {
        assertEquals(DayOfWeek.SUNDAY, parse("周天休息").day!!.dayOfWeek)
        assertEquals(DayOfWeek.SUNDAY, parse("周日休息").day!!.dayOfWeek)
        assertEquals(DayOfWeek.SUNDAY, parse("礼拜天休息").day!!.dayOfWeek)
    }

    // ---------- 相对日 ----------

    @Test
    fun `明天后天大后天`() {
        assertEquals(LocalDate.of(2026, 9, 24), parse("明天交材料").day)
        assertEquals(LocalDate.of(2026, 9, 25), parse("后天体检").day)
        assertEquals(LocalDate.of(2026, 9, 26), parse("大后天出发").day)
    }

    @Test
    fun `今天就是当天`() {
        assertEquals(LocalDate.of(2026, 9, 23), parse("今天晚上八点看电影").day)
    }

    @Test
    fun `N天后与N周后`() {
        assertEquals(LocalDate.of(2026, 9, 26), parse("3天后复诊").day)
        assertEquals(LocalDate.of(2026, 10, 7), parse("2周后交论文").day)
    }

    // ---------- 具体日期 ----------

    @Test
    fun `月日格式`() {
        assertEquals(LocalDate.of(2026, 10, 1), parse("10月1日放假").day)
    }

    @Test
    fun `今年的月日已过则顺延到明年`() {
        // 现在 9 月，说「3月5日」指的是明年 3 月 5 日。
        assertEquals(LocalDate.of(2027, 3, 5), parse("3月5日生日").day)
    }

    @Test
    fun `带年份的日期按字面取`() {
        assertEquals(LocalDate.of(2027, 3, 5), parse("2027年3月5日考试").day)
    }

    @Test
    fun `号与日等价`() {
        assertEquals(LocalDate.of(2026, 10, 8), parse("10月8号开会").day)
    }

    // ---------- 时间 ----------

    @Test
    fun `时段词决定12小时制换算`() {
        assertEquals(LocalTime.of(20, 0), parse("晚上八点吃饭").time)
        assertEquals(LocalTime.of(14, 0), parse("下午两点开会").time)
        assertEquals(LocalTime.of(8, 0), parse("上午八点上班").time)
    }

    @Test
    fun `没有时段词时按字面24小时制`() {
        // 「八点」保持 08:00：不确定就不猜，确认卡片会让用户核对。
        assertEquals(LocalTime.of(8, 0), parse("八点交班").time)
        assertEquals(LocalTime.of(20, 0), parse("20点交班").time)
    }

    @Test
    fun `点半与点分`() {
        assertEquals(LocalTime.of(9, 30), parse("九点半开会").time)
        assertEquals(LocalTime.of(9, 45), parse("九点四十五分出发").time)
    }

    @Test
    fun `冒号时间写法`() {
        assertEquals(LocalTime.of(18, 30), parse("18:30 下班").time)
        assertEquals(LocalTime.of(7, 5), parse("7：05 起床").time)
    }

    @Test
    fun `只说时段时给该时段的默认时刻`() {
        assertEquals(LocalTime.of(20, 0), parse("晚上去看电影").time)
        assertEquals(LocalTime.of(9, 0), parse("上午去银行").time)
    }

    @Test
    fun `全天事件没有具体时刻`() {
        val intent = parse("明天全天团建")
        assertTrue(intent.allDay)
        assertNull(intent.time)
    }

    // ---------- 重复 ----------

    @Test
    fun `重复规则`() {
        assertEquals(RepeatRule.DAILY, parse("每天八点吃药").repeat)
        assertEquals(RepeatRule.WEEKLY, parse("每周一开会").repeat)
        assertEquals(RepeatRule.MONTHLY, parse("每月1号交房租").repeat)
        assertEquals(RepeatRule.YEARLY, parse("每年10月1日纪念").repeat)
        assertEquals(RepeatRule.NONE, parse("这周五上课").repeat)
    }

    // ---------- 提醒提前量 ----------

    @Test
    fun `有具体时刻的事件默认提前一小时`() {
        // 用户例子：「八点上课」→ 提前一小时即七点提醒。
        val intent = parse("这周五八点去上实验课")
        assertEquals(60, intent.reminderOffsetMin)
        assertEquals(LocalTime.of(7, 0), LocalTime.from(intent.time!!.minusMinutes(60)))
    }

    @Test
    fun `全天事件在当天上午九点提醒而不是前一天半夜`() {
        // 全天事件若也「提前 1 小时」，会变成前一天 23:00，几乎必然被忽略。
        val intent = parse("明天全天团建")
        assertEquals(9 * 60, intent.reminderOffsetMin)
    }

    @Test
    fun `显式提前量优先于默认值`() {
        assertEquals(15, parse("明天下午三点开会提前15分钟").reminderOffsetMin)
        assertEquals(120, parse("后天上午十点体检提前2小时").reminderOffsetMin)
        assertEquals(24 * 60, parse("下周一交报告提前1天").reminderOffsetMin)
    }

    @Test
    fun `明确说不要提醒时不排提醒`() {
        assertEquals(EventEntity.NO_REMINDER, parse("明天下午三点开会不用提醒").reminderOffsetMin)
    }

    // ---------- 标题提取 ----------

    @Test
    fun `标题去掉时间词与语气词`() {
        assertEquals("去上实验课", parse("这周五八点记得去上实验课").title)
        assertEquals("交周报", parse("周三交周报").title)
        assertEquals("体检", parse("后天体检").title)
    }

    @Test
    fun `整句都是时间词时退回原文而不是空标题`() {
        // 标题为空会让保存按钮一直不可用，用户不知道发生了什么。
        val intent = parse("明天八点")
        assertTrue(intent.title.isNotBlank())
    }

    // ---------- 兜底 ----------

    @Test
    fun `没有日期信息时标记为不足以排期`() {
        val intent = parse("记得买牛奶")
        assertNull(intent.day)
        assertTrue(!intent.hasEnoughToSchedule)
    }

    @Test
    fun `空输入不抛异常`() {
        val intent = parse("   ")
        assertNull(intent.day)
        assertEquals("", intent.title)
    }

    @Test
    fun `命中的时间表达被记录下来供用户核对`() {
        val intent = parse("这周五八点去上实验课")
        assertTrue("应记录命中的日期文本：${intent.matchedDateText}", intent.matchedDateText.contains("周五"))
        assertTrue("应记录命中的时间文本：${intent.matchedTimeText}", intent.matchedTimeText.isNotBlank())
    }

    // ---------- AI 返回结果的二次校验 ----------

    private fun ai(raw: String, now: LocalDateTime = LocalDateTime.of(2026, 9, 23, 10, 0)) =
        app.moshu.journal.data.schedule.AiEventPlanner.parseModelJson(
            raw, now, java.time.ZoneId.of("Asia/Shanghai"), "原句",
        )

    @Test
    fun `解析 AI 返回的标准 JSON`() {
        val intent = ai("""{"title":"上实验课","date":"2026-09-25","time":"20:00","allDay":false,"repeat":"weekly","reminderOffsetMinutes":60}""")
        assertEquals("上实验课", intent!!.title)
        assertEquals(LocalDate.of(2026, 9, 25), intent.day)
        assertEquals(LocalTime.of(20, 0), intent.time)
        assertEquals(RepeatRule.WEEKLY, intent.repeat)
        assertEquals(60, intent.reminderOffsetMin)
    }

    @Test
    fun `模型把 JSON 包在代码块里也能解析`() {
        val raw = "```json\n{\"title\":\"开会\",\"date\":\"2026-09-25\",\"time\":\"14:00\"}\n```"
        assertEquals("开会", ai(raw)!!.title)
    }

    @Test
    fun `模型给出过去的日期时拒绝而不是排一个错提醒`() {
        // 让语言模型做日期算术不可靠，算到过去说明它错了；宁可让用户手填。
        assertNull(ai("""{"title":"x","date":"2020-01-01","time":"10:00"}"""))
    }

    @Test
    fun `模型给出过远的日期时拒绝`() {
        assertNull(ai("""{"title":"x","date":"2035-01-01"}"""))
    }

    @Test
    fun `模型没给日期时返回空`() {
        assertNull(ai("""{"title":"x","date":null}"""))
    }

    @Test
    fun `模型没给标题时退回原句`() {
        val intent = ai("""{"title":"","date":"2026-09-25","time":"10:00"}""")
        assertEquals("原句", intent!!.title)
    }

    @Test
    fun `模型说全天时不接受它给的时间`() {
        val intent = ai("""{"title":"团建","date":"2026-09-25","time":"10:00","allDay":true,"reminderOffsetMinutes":540}""")
        assertNull("全天事件不该带具体时刻", intent!!.time)
        assertTrue(intent.allDay)
    }

    @Test
    fun `非法的重复规则被收敛成不重复`() {
        assertEquals(RepeatRule.NONE, ai("""{"title":"x","date":"2026-09-25","repeat":"every_full_moon"}""")!!.repeat)
    }

    @Test
    fun `落库时全天取当天零点、有时刻取指定时刻`() {
        val zone = java.time.ZoneId.of("Asia/Shanghai")
        val allDay = app.moshu.journal.data.schedule.AiEventPlanner.toEntity(
            app.moshu.journal.data.schedule.EventIntentParser.Intent(
                "生日", LocalDate.of(2026, 10, 1), null, true, RepeatRule.YEARLY, 540,
            ),
            zone,
        )!!
        assertEquals(
            LocalDateTime.of(2026, 10, 1, 0, 0),
            LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(allDay.startAt), zone),
        )
        val timed = app.moshu.journal.data.schedule.AiEventPlanner.toEntity(
            app.moshu.journal.data.schedule.EventIntentParser.Intent(
                "开会", LocalDate.of(2026, 10, 1), LocalTime.of(14, 30), false, RepeatRule.NONE, 30,
            ),
            zone,
        )!!
        assertEquals(
            LocalDateTime.of(2026, 10, 1, 14, 30),
            LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(timed.startAt), zone),
        )
    }
}
