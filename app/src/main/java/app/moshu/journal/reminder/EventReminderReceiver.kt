package app.moshu.journal.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.moshu.journal.MoShuApp
import app.moshu.journal.data.db.RepeatRule
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 事件提醒到点后的接收器。
 *
 * 只做三件事：发通知、把重复事件排到下一次、把结果写进事件表。
 * 不做长耗时工作（广播接收器有 10 秒上限）。
 */
class EventReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(EXTRA_EVENT_ID, 0L)
        if (id <= 0L) return
        val app = context.applicationContext as? MoShuApp ?: return
        // BroadcastReceiver 的 onReceive 在主线程，这里用 goAsync 换取写库时间；
        // DAO 是挂起函数，所以在后台线程里用 runBlocking 收口，避免引入额外作用域。
        val pending = goAsync()
        Thread {
            try {
                kotlinx.coroutines.runBlocking {
                val dao = app.database.eventDao()
                val event = dao.byId(id) ?: return@runBlocking
                if (event.done || !event.hasReminder) return@runBlocking

                val zone = ZoneId.systemDefault()
                val now = System.currentTimeMillis()
                val formatter = DateTimeFormatter.ofPattern("M月d日 HH:mm", Locale.CHINA)
                val whenText = java.time.Instant.ofEpochMilli(event.startAt).atZone(zone).format(formatter)
                val lateMs = now - event.startAt
                val body = buildString {
                    append(whenText)
                    if (lateMs > 5 * 60_000L) {
                        // 设备休眠导致迟发时如实说明，否则用户会以为提醒设错了时间。
                        append("（设备休眠，延迟 ")
                        append(lateMs / 60_000)
                        append(" 分钟送达）")
                    }
                    if (event.note.isNotBlank()) append('\n').append(event.note)
                }
                Notifications.showEvent(context, event, body)

                // 重复事件：排下一次。一次性事件保持原样，由用户自己勾完成。
                if (event.repeatRule != RepeatRule.NONE) {
                    val next = RepeatRule.nextAfter(event, now, zone)
                    if (next != null) {
                        val advanced = event.copy(startAt = next, updatedAt = now)
                        dao.update(advanced)
                        EventReminderScheduler.schedule(context, advanced, now)
                    }
                }
                }
            } catch (_: Throwable) {
                // 提醒失败不该让接收器崩溃；下一次启动重排会再试。
            } finally {
                pending.finish()
            }
        }.apply { isDaemon = true }.start()
    }

    companion object {
        const val EXTRA_EVENT_ID = "event_id"
    }
}
