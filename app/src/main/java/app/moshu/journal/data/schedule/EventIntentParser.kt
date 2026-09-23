package app.moshu.journal.data.schedule

import app.moshu.journal.data.db.EventEntity
import app.moshu.journal.data.db.RepeatRule
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * 从一句中文里解析出「什么时候做什么」。
 *
 * 为什么要有这个本地解析器，而不是全靠 AI：
 *  - 离线/未配置 AI 时也能用；
 *  - 瞬间出结果，用户说完就能看到解析预览；
 *  - 结果是确定性的，可以单测覆盖——日期算错是这类功能最致命的 bug，
 *    而 AI 每次给的答案可能不同，测不稳。
 * AI 只在本地解析不出时兜底（见 AiEventPlanner）。
 *
 * 解析结果一律先给用户确认再落库：把「下周五」误判成「本周五」比不安排更糟。
 */
object EventIntentParser {

    /** 解析结果。不确定的部分留空，由界面让用户补。 */
    data class Intent(
        val title: String,
        val day: LocalDate?,
        val time: LocalTime?,
        val allDay: Boolean,
        val repeat: String,
        val reminderOffsetMin: Int,
        /** 命中的时间表达原文，用于向用户解释「我是从哪句读出来的」。 */
        val matchedDateText: String = "",
        val matchedTimeText: String = "",
    ) {
        val hasEnoughToSchedule: Boolean get() = day != null
    }

    private val WEEKDAY_CHARS = mapOf(
        '一' to DayOfWeek.MONDAY, '二' to DayOfWeek.TUESDAY, '三' to DayOfWeek.WEDNESDAY,
        '四' to DayOfWeek.THURSDAY, '五' to DayOfWeek.FRIDAY, '六' to DayOfWeek.SATURDAY,
        '日' to DayOfWeek.SUNDAY, '天' to DayOfWeek.SUNDAY,
    )

    /** 时段的粗粒度偏移。带这些词时按 12 小时制换算。 */
    private val PERIODS = listOf(
        "凌晨" to 0, "清晨" to 0, "早上" to 0, "早晨" to 0, "上午" to 0, "早" to 0,
        "中午" to 12, "正午" to 12,
        "下午" to 12, "傍晚" to 12, "晚上" to 12, "晚" to 12, "夜里" to 12, "夜间" to 12,
    )

    /**
     * 解析。
     *
     * [now] 与 [zone] 可注入，便于测试跨日、跨周、跨月边界。
     */
    fun parse(
        raw: String,
        now: LocalDateTime = LocalDateTime.now(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): Intent {
        val text = raw.trim()
        if (text.isBlank()) return Intent("", null, null, false, RepeatRule.NONE, EventEntity.NO_REMINDER)

        val repeat = parseRepeat(text)
        val (day, dateText) = parseDay(text, now)
        val (time, timeText, allDay) = parseTime(text, day ?: now.toLocalDate(), now)
        val reminder = parseReminder(text, allDay)
        val title = extractTitle(text)

        return Intent(
            title = title,
            day = day,
            time = time,
            allDay = allDay,
            repeat = repeat,
            reminderOffsetMin = reminder,
            matchedDateText = dateText,
            matchedTimeText = timeText,
        )
    }

    // ---------- 重复 ----------

    private fun parseRepeat(text: String): String = when {
        Regex("每天|每日|天天").containsMatchIn(text) -> RepeatRule.DAILY
        Regex("每周|每星期|每礼拜").containsMatchIn(text) -> RepeatRule.WEEKLY
        Regex("每月").containsMatchIn(text) -> RepeatRule.MONTHLY
        Regex("每年").containsMatchIn(text) -> RepeatRule.YEARLY
        else -> RepeatRule.NONE
    }

    // ---------- 日期 ----------

    private fun parseDay(text: String, now: LocalDateTime): Pair<LocalDate?, String> {
        val today = now.toLocalDate()

        // 具体日期优先：「3月5日」比「周五」更明确。
        Regex("(\\d{1,2})\\s*月\\s*(\\d{1,2})\\s*[日号]").find(text)?.let { m ->
            val month = m.groupValues[1].toIntOrNull() ?: return@let
            val dayOfMonth = m.groupValues[2].toIntOrNull() ?: return@let
            if (month !in 1..12 || dayOfMonth !in 1..31) return@let
            // 该日期今年已过则顺延到明年：说「3月5日」时如果已经 6 月，指的是明年。
            var candidate = runCatching { LocalDate.of(today.year, month, dayOfMonth) }.getOrNull() ?: return@let
            if (candidate.isBefore(today)) {
                candidate = runCatching { LocalDate.of(today.year + 1, month, dayOfMonth) }.getOrNull() ?: candidate
            }
            return candidate to m.value
        }

        Regex("(\\d{4})\\s*年\\s*(\\d{1,2})\\s*月\\s*(\\d{1,2})\\s*[日号]?").find(text)?.let { m ->
            val year = m.groupValues[1].toIntOrNull() ?: return@let
            val month = m.groupValues[2].toIntOrNull() ?: return@let
            val dayOfMonth = m.groupValues[3].toIntOrNull() ?: return@let
            val candidate = runCatching { LocalDate.of(year, month, dayOfMonth) }.getOrNull() ?: return@let
            return candidate to m.value
        }

        // 相对日
        Regex("大后天").find(text)?.let { return today.plusDays(3) to it.value }
        Regex("后天").find(text)?.let { return today.plusDays(2) to it.value }
        Regex("明天|明日|明早|明晚").find(text)?.let { return today.plusDays(1) to it.value }
        Regex("今天|今日|今晚|今早").find(text)?.let { return today to it.value }

        // 星期几：这周X / 本周X / 下X周X / 周X / 星期X / 礼拜X
        Regex("(下{1,2}|这|本)?\\s*(?:周|星期|礼拜)\\s*([一二三四五六日天])").find(text)?.let { m ->
            val prefix = m.groupValues[1]
            val target = WEEKDAY_CHARS[m.groupValues[2].first()] ?: return@let
            val base = when (prefix) {
                "下" -> today.with(TemporalAdjusters.next(target)).with(TemporalAdjusters.next(target))
                "下下" -> today.with(TemporalAdjusters.next(target)).with(TemporalAdjusters.next(target))
                    .with(TemporalAdjusters.next(target))
                else -> today.with(TemporalAdjusters.nextOrSame(target))
            }
            return base to m.value
        }

        // 「N 天后」「N 周后」
        Regex("(\\d{1,3})\\s*天\\s*后").find(text)?.let { m ->
            val n = m.groupValues[1].toIntOrNull() ?: return@let
            return today.plusDays(n.toLong()) to m.value
        }
        Regex("(\\d{1,2})\\s*(?:周|星期)\\s*后").find(text)?.let { m ->
            val n = m.groupValues[1].toIntOrNull() ?: return@let
            return today.plusWeeks(n.toLong()) to m.value
        }

        return null to ""
    }

    // ---------- 时间 ----------

    /** @return (时刻, 命中原文, 是否全天) */
    private fun parseTime(text: String, day: LocalDate, now: LocalDateTime): Triple<LocalTime?, String, Boolean> {
        // 「全天」明确说了就按全天。
        if (Regex("全天|一整天").containsMatchIn(text)) return Triple(null, "全天", true)

        val period = PERIODS.firstOrNull { text.contains(it.first) }
        val periodShift = period?.second ?: 0

        // 中文数字转阿拉伯，让「八点」也能解析。
        val normalized = normalizeChineseNumbers(text)

        Regex("(\\d{1,2})\\s*[点时]\\s*(半|(\\d{1,2})\\s*分)?").find(normalized)?.let { m ->
            var hour = m.groupValues[1].toIntOrNull() ?: return@let
            val minute = when {
                m.groupValues[2] == "半" -> 30
                m.groupValues[3].isNotBlank() -> m.groupValues[3].toIntOrNull()?.coerceIn(0, 59) ?: 0
                else -> 0
            }
            // 12 小时制换算：只有带时段词、且小时在 12 以内时才加 12。
            // 「晚上八点」→20:00；「八点」保持 08:00（不确定就按字面来，
            // 反正确认卡片会让用户核对）。
            if (periodShift == 12 && hour in 1..11) hour += 12
            if (period != null && period.first == "中午" && hour in 1..11) hour += 12
            if (hour !in 0..23) return@let
            return Triple(LocalTime.of(hour, minute), m.value, false)
        }

        // 「8:30」这种写法
        Regex("(\\d{1,2})\\s*[:：]\\s*(\\d{1,2})").find(normalized)?.let { m ->
            val hour = m.groupValues[1].toIntOrNull() ?: return@let
            val minute = m.groupValues[2].toIntOrNull() ?: return@let
            if (hour !in 0..23 || minute !in 0..59) return@let
            return Triple(LocalTime.of(hour, minute), m.value, false)
        }

        // 只说了时段没说具体点：给一个该时段的默认时刻。
        if (period != null) {
            val hour = when (period.first) {
                "凌晨" -> 5
                "清晨", "早上", "早晨", "上午", "早" -> 9
                "中午", "正午" -> 12
                "下午" -> 14
                "傍晚" -> 18
                else -> 20
            }
            return Triple(LocalTime.of(hour, 0), period.first, false)
        }

        return Triple(null, "", false)
    }

    /**
     * 中文数字 → 阿拉伯数字。
     *
     * 只处理 0-99 的常见写法（「八」→8、「十二」→12、「二十」→20），
     * 不做通用中文数字解析：提醒场景里不会出现「三千」这种量级。
     */
    internal fun normalizeChineseNumbers(text: String): String {
        val digits = mapOf(
            '零' to 0, '〇' to 0, '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4,
            '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9, '十' to 10,
        )
        // 只转换紧挨着「点/时/分」的数词，避免把标题里的数字也换掉。
        // 必须同时覆盖「分」：「九点四十五分」里的四十五在「分」之前，
        // 只认「点/时」会让它保持中文，时间就被读成 9 点整。
        return Regex("([零〇一二两三四五六七八九十]{1,3})\\s*(?=[点时]|分)").replace(text) { m ->
            val run = m.groupValues[1]
            // 贪婪匹配会把前一个字也吃进来（「这周五八点」里匹配到「五八」），
            // 所以从最长开始试、失败就退到更短的尾部，而不是整段放弃——
            // 早先直接放弃会让「这周五八点」完全解析不出时间。
            for (length in run.length downTo 1) {
                val candidate = run.takeLast(length)
                chineseToInt(candidate, digits)?.let { return@replace it.toString() }
            }
            run
        }
    }

    /** 单个中文数词转整数；不是合法数词时返回 null（由调用方继续缩短再试）。 */
    private fun chineseToInt(s: String, digits: Map<Char, Int>): Int? = when {
        s == "十" -> 10
        s.length == 1 -> digits[s[0]]
        s.startsWith("十") -> digits[s[1]]?.let { 10 + it }
        s.endsWith("十") -> digits[s[0]]?.let { it * 10 }
        s.length == 3 && s[1] == '十' -> {
            val tens = digits[s[0]]
            val ones = digits[s[2]]
            if (tens != null && ones != null) tens * 10 + ones else null
        }
        else -> null
    }

    // ---------- 提醒提前量 ----------

    /**
     * 解析提前量。
     *
     * 默认值按事件类型给：有具体时刻的事件提前 1 小时（开会/上课最常用的提前量），
     * 全天事件在当天 09:00 提醒——全天事件若也「提前 1 小时」，会变成前一天 23:00 提醒，
     * 那是半夜，几乎必然被忽略。
     */
    private fun parseReminder(text: String, allDay: Boolean): Int {
        Regex("提前\\s*(\\d{1,3})\\s*(分钟|分)").find(text)?.let { m ->
            return m.groupValues[1].toIntOrNull()?.coerceIn(0, 7 * 24 * 60) ?: defaultReminder(allDay)
        }
        Regex("提前\\s*(\\d{1,2})\\s*(小时|时)").find(text)?.let { m ->
            return (m.groupValues[1].toIntOrNull() ?: 1) * 60
        }
        Regex("提前\\s*(\\d{1,2})\\s*天").find(text)?.let { m ->
            return (m.groupValues[1].toIntOrNull() ?: 1) * 24 * 60
        }
        if (Regex("不用提醒|不要提醒|无需提醒").containsMatchIn(text)) return EventEntity.NO_REMINDER
        return defaultReminder(allDay)
    }

    private fun defaultReminder(allDay: Boolean): Int =
        if (allDay) ALL_DAY_REMINDER_OFFSET_MIN else DEFAULT_REMINDER_OFFSET_MIN

    /** 全天事件在当天 09:00 提醒：00:00 减 9 小时。 */
    private const val ALL_DAY_REMINDER_OFFSET_MIN = 9 * 60

    /** 有具体时刻的事件默认提前一小时。 */
    private const val DEFAULT_REMINDER_OFFSET_MIN = 60

    // ---------- 标题 ----------

    /** 时间词之外的剩余部分就是「要做什么」。 */
    private fun extractTitle(text: String): String {
        var t = text
        // 去掉语气词与提醒类动词，它们不是内容。
        t = Regex("^(记得|别忘了|别忘记|提醒我|要|去|帮我|请)").replace(t, "")
        t = Regex("(记得|别忘了|别忘记|提醒我)").replace(t, "")
        t = Regex("(提前\\s*\\d{1,3}\\s*(分钟|分|小时|时|天))").replace(t, "")
        t = Regex("(不用提醒|不要提醒|无需提醒|全天|一整天)").replace(t, "")
        t = Regex("(今天|今日|今晚|今早|明天|明日|明早|明晚|后天|大后天)").replace(t, "")
        t = Regex("(下{1,2}|这|本)?\\s*(周|星期|礼拜)\\s*[一二三四五六日天]").replace(t, "")
        t = Regex("\\d{4}\\s*年\\s*\\d{1,2}\\s*月\\s*\\d{1,2}\\s*[日号]?").replace(t, "")
        t = Regex("\\d{1,2}\\s*月\\s*\\d{1,2}\\s*[日号]").replace(t, "")
        t = Regex("\\d{1,3}\\s*天\\s*后").replace(t, "")
        t = Regex("\\d{1,2}\\s*(周|星期)\\s*后").replace(t, "")
        t = Regex("(每天|每日|天天|每周|每星期|每礼拜|每月|每年)").replace(t, "")
        t = Regex("(凌晨|清晨|早上|早晨|上午|早|中午|正午|下午|傍晚|晚上|晚|夜里|夜间)").replace(t, "")
        t = Regex("[零〇一二两三四五六七八九十\\d]{1,3}\\s*[点时](\\s*(半|[零〇一二两三四五六七八九十\\d]{1,2}\\s*分))?").replace(t, "")
        t = Regex("\\d{1,2}\\s*[:：]\\s*\\d{1,2}").replace(t, "")
        t = Regex("^[的、，,。\\s]+").replace(t, "")
        t = Regex("[的\\s]+$").replace(t, "")
        return t.trim().ifBlank { text.trim() }
    }
}
