package app.moshu.journal.ui.insights

import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * 全年热力图的纯计算部分。
 *
 * 单独抽出来是因为这里有真正的算术：周对齐、天数补齐、颜色分档。
 * 混在 ViewModel 和 Composable 里就只能靠肉眼在手机上数格子对不对。
 */
object YearActivity {

    /** 展示 53 列，刚好一年（含闰年 366 天）再加一点余量。 */
    const val WEEKS = 53

    /** 热力图的起点：往前推满 [WEEKS] - 1 周的那个周一。 */
    fun firstMonday(today: LocalDate): LocalDate {
        val thisMonday = today.minusDays((today.dayOfWeek.value - 1).toLong())
        return thisMonday.minusWeeks((WEEKS - 1).toLong())
    }

    /**
     * 把「某天写了多少条」摊成从 [firstMonday] 到今天、逐日连续的列表。
     *
     * 没有记录的日期也要占位，否则热力图会把空白日子直接跳过，
     * 整年的形状就全错位了。
     */
    fun buckets(countsByDay: Map<LocalDate, Int>, today: LocalDate): List<YearDay> {
        val start = firstMonday(today)
        val days = ChronoUnit.DAYS.between(start, today).toInt() + 1
        return (0 until days).map { offset ->
            val date = start.plusDays(offset.toLong())
            YearDay(date, countsByDay[date] ?: 0)
        }
    }

    /** 0 条是灰的，其余按当天条数分四档，避免「写 1 条」和「写 10 条」看起来一样深。 */
    val LEVEL_ALPHA = listOf(0f, 0.3f, 0.5f, 0.72f, 1f)

    /**
     * 哪些列要标月份，返回「列下标 → 月份」。
     *
     * 判据是「这一周里有某月 1 号」。不能用 dayOfMonth <= 7：当月 1 号落在周中时，
     * 下一周还含 6、7 号，于是连着两列都标上同一个月，看起来像文字重叠。
     */
    fun monthLabels(weeks: List<List<YearDay>>): Map<Int, Int> = buildMap {
        weeks.forEachIndexed { index, week ->
            week.firstOrNull { it.date.dayOfMonth == 1 }?.let { put(index, it.date.monthValue) }
        }
    }

    fun level(count: Int, maxCount: Int): Int {
        if (count <= 0) return 0
        // 全年最多也就 1 条时不该把唯一的一格画成最深色——它并不是「高产」。
        if (maxCount <= 1) return 2
        return (1 + (count - 1) * 3 / (maxCount - 1)).coerceIn(1, 4)
    }

    /** 日期转本地时区当天的零点，用于给 Room 的区间查询算边界。 */
    fun startOfDay(date: LocalDate, zone: ZoneId): Long =
        date.atStartOfDay(zone).toInstant().toEpochMilli()
}