package app.moshu.journal.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * 自定义事件：可以指定日期时间、重复规则，并**单独挑选提醒铃声**。
 *
 * 与 [TodoEntity] 的分工：待办是「要做的事」，只需要一个截止日；
 * 事件是「某个时刻要发生的事」（生日、纪念日、会议、吃药），时间点必须准确，
 * 而且不同事件适合不同铃声——把吃药和开会用同一个提示音，用户根本分不清。
 */
@Entity(
    tableName = "events",
    indices = [Index(value = ["uid"], unique = true), Index("startAt")],
)
data class EventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(defaultValue = "''") val uid: String = UUID.randomUUID().toString(),
    val title: String,
    @ColumnInfo(defaultValue = "''") val note: String = "",
    /** 事件发生时刻（毫秒）。全天事件取当天 00:00。 */
    val startAt: Long,
    @ColumnInfo(defaultValue = "0") val allDay: Boolean = false,
    /** none / daily / weekly / monthly / yearly */
    @ColumnInfo(defaultValue = "'none'") val repeatRule: String = RepeatRule.NONE,
    /**
     * 提前多少分钟提醒；[NO_REMINDER] 表示不提醒，0 表示准点提醒。
     *
     * 用「相对偏移」而不是绝对时间戳：后者在跨时区或改系统时间后就会指向错误的本地时刻。
     */
    @ColumnInfo(defaultValue = "-1") val reminderOffsetMin: Int = NO_REMINDER,
    /** 该事件专属的提示音 URI；空串表示跟随系统默认。 */
    @ColumnInfo(defaultValue = "''") val soundUri: String = "",
    /** 铃声的可读名字，通知与列表里展示用；系统只给 URI，名字要自己存。 */
    @ColumnInfo(defaultValue = "''") val soundLabel: String = "",
    /** default / high：high 会以横幅形式弹出，适合不能错过的提醒。 */
    @ColumnInfo(defaultValue = "'default'") val importance: String = Importance.DEFAULT,
    @ColumnInfo(defaultValue = "0") val done: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = createdAt,
) {
    val hasReminder: Boolean get() = reminderOffsetMin != NO_REMINDER

    /** 提醒应该触发的绝对时刻；不提醒时返回 null。 */
    val remindAt: Long? get() = if (hasReminder) startAt - reminderOffsetMin * 60_000L else null

    companion object {
        const val NO_REMINDER = -1
    }
}

/** 重复规则。取值直接落库，改动需同步迁移。 */
object RepeatRule {
    const val NONE = "none"
    const val DAILY = "daily"
    const val WEEKLY = "weekly"
    const val MONTHLY = "monthly"
    const val YEARLY = "yearly"

    val ALL = listOf(NONE, DAILY, WEEKLY, MONTHLY, YEARLY)

    fun label(rule: String): String = when (rule) {
        DAILY -> "每天"
        WEEKLY -> "每周"
        MONTHLY -> "每月"
        YEARLY -> "每年"
        else -> "不重复"
    }

    /**
     * 下一次发生的时刻。
     *
     * 逐次按日历单位推进而不是「加固定毫秒数」：后者在跨夏令时、跨月长度不同时会漂移
     * （例如每月 31 号的事件加 30 天会跑到下个月 1 号）。
     * 遇到当月不存在的日期（1 月 31 日的「每月」在 2 月）时取当月最后一天，
     * 而不是顺延到下个月——用户心里那是「这个月的这件事」。
     */
    fun nextAfter(event: EventEntity, now: Long, zone: ZoneId = ZoneId.systemDefault()): Long? {
        if (event.repeatRule == NONE) return null
        val start = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(event.startAt), zone)
        if (event.startAt > now) return event.startAt
        val current = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(now), zone)
        var candidate = start
        // 循环次数有上限：极端参数（例如 1970 年的每天事件）下不能无限推进。
        var guard = 0
        while (!candidate.isAfter(current) && guard++ < MAX_ADVANCE_STEPS) {
            candidate = advance(candidate, event.repeatRule)
        }
        return if (candidate.isAfter(current)) candidate.atZone(zone).toInstant().toEpochMilli() else null
    }

    private fun advance(from: LocalDateTime, rule: String): LocalDateTime = when (rule) {
        DAILY -> from.plusDays(1)
        WEEKLY -> from.plusWeeks(1)
        MONTHLY -> plusMonthsClamped(from, 1)
        YEARLY -> plusMonthsClamped(from, 12)
        else -> from
    }

    /** 加月份并夹到当月最后一天：1 月 31 日 + 1 月 = 2 月 28/29 日，而不是 3 月 3 日。 */
    private fun plusMonthsClamped(from: LocalDateTime, months: Long): LocalDateTime {
        val target = from.plusMonths(months)
        return if (target.dayOfMonth == from.dayOfMonth) target
        else target.withDayOfMonth(target.toLocalDate().lengthOfMonth())
    }

    private const val MAX_ADVANCE_STEPS = 5_000
}

object Importance {
    const val DEFAULT = "default"
    const val HIGH = "high"
}

/** 事件所属的自然日（本地时区）。日历分组与筛选共用这一套换算。 */
fun EventEntity.eventDay(zone: ZoneId = ZoneId.systemDefault()): LocalDate =
    java.time.Instant.ofEpochMilli(startAt).atZone(zone).toLocalDate()

/** 事件的本地时刻；全天事件返回当天 00:00。 */
fun EventEntity.eventTime(zone: ZoneId = ZoneId.systemDefault()): LocalTime =
    java.time.Instant.ofEpochMilli(startAt).atZone(zone).toLocalTime().truncatedTo(ChronoUnit.MINUTES)
