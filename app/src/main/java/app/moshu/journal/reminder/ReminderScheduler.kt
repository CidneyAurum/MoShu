package app.moshu.journal.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** 每日日志提醒使用十分钟窗口，无需精确闹钟特殊权限。 */
object ReminderScheduler {

    private const val REQUEST_CODE = 2001

    private fun pendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, ReminderReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    fun schedule(context: Context, hour: Int, minute: Int) {
        val alarm = context.getSystemService(AlarmManager::class.java) ?: return
        val triggerAt = nextTriggerTime(hour, minute)
        val pi = pendingIntent(context)
        alarm.setWindow(AlarmManager.RTC_WAKEUP, triggerAt, 10 * 60_000L, pi)
    }

    fun cancel(context: Context) {
        val alarm = context.getSystemService(AlarmManager::class.java) ?: return
        alarm.cancel(pendingIntent(context))
    }

    /**
     * 用 ZonedDateTime 而不是 Calendar：夏令时切换日里不存在的本地时刻（例如春季 02:30）
     * 会被 Calendar 静默规范化成 03:30，提醒就晚了一小时。ZonedDateTime 会把这类时刻
     * 推进到下一个真实存在的瞬间，语义明确。
     */
    fun nextTriggerTime(hour: Int, minute: Int, now: Long = System.currentTimeMillis()): Long {
        val zone = ZoneId.systemDefault()
        val time = LocalTime.of(hour.coerceIn(0, 23), minute.coerceIn(0, 59))
        val today = java.time.Instant.ofEpochMilli(now).atZone(zone)
        var candidate = LocalDate.from(today).atTime(time).atZone(zone)
        if (!candidate.toInstant().isAfter(java.time.Instant.ofEpochMilli(now))) {
            candidate = LocalDate.from(today).plusDays(1).atTime(time).atZone(zone)
        }
        return candidate.toInstant().toEpochMilli()
    }
}
