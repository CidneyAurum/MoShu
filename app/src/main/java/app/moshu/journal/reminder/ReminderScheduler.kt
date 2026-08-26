package app.moshu.journal.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.util.Calendar

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

    fun nextTriggerTime(hour: Int, minute: Int): Long {
        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (calendar.timeInMillis <= System.currentTimeMillis()) {
            calendar.add(Calendar.DAY_OF_YEAR, 1)
        }
        return calendar.timeInMillis
    }
}
