package app.moshu.journal.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.moshu.journal.MoShuApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** 重启后恢复已启用的提醒 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val result = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val app = appContext as MoShuApp
                if (app.settings.reminderEnabled.first()) {
                    val (hour, minute) = app.settings.reminderTime.first()
                    ReminderScheduler.schedule(appContext, hour, minute)
                }
            } finally {
                result.finish()
            }
        }
    }
}
