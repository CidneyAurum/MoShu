package app.moshu.journal.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.moshu.journal.MoShuApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** 重启、时区或系统时间变化后恢复已启用的提醒。 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // 只监听 BOOT_COMPLETED 的话，改时区或改系统时间后闹钟仍按旧时刻触发
        // （setWindow 存的是绝对时刻），用户会看到提醒在错误的时间弹出。
        if (intent.action !in HANDLED_ACTIONS) return
        val result = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val app = appContext as MoShuApp
                if (app.settings.reminderEnabled.first()) {
                    val (hour, minute) = app.settings.reminderTime.first()
                    ReminderScheduler.schedule(appContext, hour, minute)
                }
                // 待办提醒同样存的是绝对时刻，跨时区后要按新的本地 09:00 重排。
                TodoReminderWorker.rescheduleAll(appContext)
            } finally {
                result.finish()
            }
        }
    }

    private companion object {
        val HANDLED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_TIME_CHANGED,
        )
    }
}
