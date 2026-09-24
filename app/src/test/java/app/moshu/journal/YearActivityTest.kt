package app.moshu.journal

import app.moshu.journal.ui.insights.YearActivity
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 全年热力图的对齐与分档。这两处算错只会表现成「格子看着有点怪」，必须有断言盯着。 */
class YearActivityTest {

    private val monday = LocalDate.of(2026, 9, 21) // 2026-09-21 是周一

    @Test
    fun `今天是周一时起点正好是 52 周前的周一`() {
        assertEquals(DayOfWeek.MONDAY, monday.dayOfWeek)
        val start = YearActivity.firstMonday(monday)
        assertEquals(DayOfWeek.MONDAY, start.dayOfWeek)
        assertEquals(monday.minusWeeks(52), start)
    }

    @Test
    fun `今天是周日时仍对齐到本周一而不是下周一`() {
        val sunday = monday.plusDays(6)
        assertEquals(DayOfWeek.SUNDAY, sunday.dayOfWeek)
        assertEquals(monday.minusWeeks(52), YearActivity.firstMonday(sunday))
    }

    @Test
    fun `桶列表从起点到今天逐日连续`() {
        val days = YearActivity.buckets(emptyMap(), monday)
        assertEquals(YearActivity.firstMonday(monday), days.first().date)
        assertEquals(monday, days.last().date)
        // 52 周（364 天）加当天。
        assertEquals(365, days.size)
        assertEquals(0, days.first().count)
        days.zipWithNext().forEach { (a, b) ->
            assertEquals(a.date.plusDays(1), b.date)
        }
    }

    @Test
    fun `桶列表按周切成整列`() {
        val days = YearActivity.buckets(emptyMap(), monday)
        val weeks = days.chunked(7)
        assertEquals(YearActivity.WEEKS, weeks.size)
        // 起点是周一、终点也是周一，所以最后一列只有当天一格，渲染时要补 6 个空格。
        assertTrue(weeks.dropLast(1).all { it.size == 7 })
        assertEquals(1, weeks.last().size)
        assertEquals(monday, weeks.last().last().date)
    }

    @Test
    fun `没有记录的日期补 0 而不是被跳过`() {
        val counts = mapOf(monday to 3, monday.minusDays(400) to 9)
        val days = YearActivity.buckets(counts, monday)
        assertEquals(3, days.last().count)
        assertEquals(1, days.count { it.count > 0 })
    }

    @Test
    fun `分档把 0 条固定为最浅`() {
        assertEquals(0, YearActivity.level(0, 10))
        assertEquals(0, YearActivity.level(-1, 10))
    }

    @Test
    fun `分档随条数单调不减`() {
        val max = 20
        val levels = (0..max).map { YearActivity.level(it, max) }
        levels.zipWithNext().forEach { (a, b) -> assertTrue("$levels", a <= b) }
        assertEquals(1, levels[1])
        assertEquals(4, levels[max])
    }

    @Test
    fun `只有一条记录时不要画成最深色`() {
        assertEquals(2, YearActivity.level(1, 1))
        assertEquals(2, YearActivity.level(1, 0))
    }

    @Test
    fun `分档结果永远落在有效下标内`() {
        val alphas = YearActivity.LEVEL_ALPHA
        for (max in 1..30) {
            for (count in 0..max + 5) {
                val index = YearActivity.level(count, max)
                assertTrue("count=$count max=$max → $index", index in alphas.indices)
            }
        }
    }

    @Test
    fun `每列最多一个月份标签 且每个月份只标一次`() {
        val days = YearActivity.buckets(emptyMap(), monday)
        val labels = YearActivity.monthLabels(days.chunked(7))
        // 一周只能属于一个「1 号」，所以列下标不可能重复（Map 天然保证），
        // 真正要盯的是月份不重复出现。
        assertEquals(labels.values.toList(), labels.values.toList().distinct())
        assertEquals(labels.size, labels.values.distinct().size)
    }

    @Test
    fun `1 号落在周中时下一周不再标同一个月`() {
        // 2026-04-01 是周三：这一周含 4月1-5 日，下一周含 4月6-12 日。
        // 用 dayOfMonth <= 7 判据时两周都会命中，标签就会重叠。
        val today = LocalDate.of(2026, 4, 20)
        val weeks = YearActivity.buckets(emptyMap(), today).chunked(7)
        val labels = YearActivity.monthLabels(weeks)
        val aprilColumns = labels.filterValues { it == 4 }.keys
        assertEquals("4 月只应标一列，实际 $aprilColumns", 1, aprilColumns.size)
    }

    @Test
    fun `标签只落在含 1 号的那一列`() {
        val today = LocalDate.of(2026, 12, 31)
        val weeks = YearActivity.buckets(emptyMap(), today).chunked(7)
        val labels = YearActivity.monthLabels(weeks)
        labels.forEach { (index, month) ->
            assertTrue(
                "第 $index 列不含 ${month}月1日",
                weeks[index].any { it.date.dayOfMonth == 1 && it.date.monthValue == month },
            )
        }
    }

    @Test
    fun `跨夏令时的时区边界仍落在当天零点`() {
        val zone = ZoneId.of("America/New_York")
        val date = LocalDate.of(2026, 3, 8) // 美东当天凌晨发生夏令时切换
        val start = YearActivity.startOfDay(date, zone)
        val back = java.time.Instant.ofEpochMilli(start).atZone(zone).toLocalDate()
        assertEquals(date, back)
    }
}