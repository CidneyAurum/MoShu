package app.moshu.journal.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import app.moshu.journal.data.db.EventEntity
import app.moshu.journal.data.db.RepeatRule
import java.time.ZoneId

/**
 * 事件提醒的排期。
 *
 * 用 AlarmManager 而不是 WorkManager：事件是「14:30 的会」这类到点必须响的提醒，
 * WorkManager 允许的延迟（Doze 下可到十几分钟）会让提醒失去意义。
 *
 * Android 12+ 起精确闹钟需要 SCHEDULE_EXACT_ALARM 授权。**没有授权时不静默失准**：
 * 退回 `setWindow` 并让界面明确告诉用户「提醒可能延迟」——用户宁可知道会晚，
 * 也不能以为设了 14:30 就一定会 14:30 响。
 */
object EventReminderScheduler {

    private const val REQUEST_CODE_BASE = 40_000

    /** 无精确闹钟权限时的容差窗口。 */
    private const val INEXACT_WINDOW_MS = 10 * 60_000L

    /** 该事件下一次应该在什么时刻响；不提醒或已过期返回 null。 */
    fun nextTriggerAt(event: EventEntity, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): Long? {
        if (!event.hasReminder || event.done) return null
        val offsetMs = event.reminderOffsetMin * 60_000L
        // 重复事件按「下一次发生」重算，而不是拿原始 startAt 减偏移——
        // 后者对每天重复的事件只在第一天有效。
        // 一次性事件直接用 startAt：nextAfter 对 NONE 返回 null（它表达的是「下一次」），
        // 早先直接拿它当基准会让**所有一次性事件都排不上提醒**（单测抓到过）。
        val base = if (event.repeatRule == RepeatRule.NONE) event.startAt
        else RepeatRule.nextAfter(event, now, zone) ?: return null
        val at = base - offsetMs
        return if (at > now) at else null
    }

    fun canScheduleExact(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val alarm = context.getSystemService(AlarmManager::class.java) ?: return false
        return alarm.canScheduleExactAlarms()
    }

    fun schedule(context: Context, event: EventEntity, now: Long = System.currentTimeMillis()): Boolean {
        val at = nextTriggerAt(event, now) ?: run {
            cancel(context, event)
            return false
        }
        val alarm = context.getSystemService(AlarmManager::class.java) ?: return false
        val pi = pendingIntent(context, event)
        return runCatching {
            if (canScheduleExact(context)) {
                alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } else {
                alarm.setWindow(AlarmManager.RTC_WAKEUP, at, INEXACT_WINDOW_MS, pi)
            }
            true
        }.getOrDefault(false)
    }

    fun cancel(context: Context, event: EventEntity) {
        val alarm = context.getSystemService(AlarmManager::class.java) ?: return
        runCatching { alarm.cancel(pendingIntent(context, event)) }
    }

    /** 启动、时区或系统时间变化后重排全部事件提醒。 */
    suspend fun rescheduleAll(context: Context) {
        val db = (context.applicationContext as app.moshu.journal.MoShuApp).database
        val now = System.currentTimeMillis()
        db.eventDao().allOnce().forEach { event ->
            if (event.hasReminder && !event.done) schedule(context, event, now)
        }
    }

    /**
     * requestCode 必须能区分事件，否则后设的提醒会顶掉先设的。
     * 用 id 而不是 uid.hashCode()：后者可能碰撞，而 id 由数据库保证唯一。
     */
    private fun pendingIntent(context: Context, event: EventEntity): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            REQUEST_CODE_BASE + event.id.toInt(),
            Intent(context, EventReminderReceiver::class.java).apply {
                putExtra(EventReminderReceiver.EXTRA_EVENT_ID, event.id)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}
