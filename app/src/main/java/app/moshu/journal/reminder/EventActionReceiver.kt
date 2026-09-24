package app.moshu.journal.reminder

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.moshu.journal.MoShuApp
import app.moshu.journal.data.db.EventEntity
import app.moshu.journal.data.db.RepeatRule
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 通知上的「完成 / 推迟」按钮。
 *
 * 提醒弹出时用户往往正在忙别的事：要打开应用、找到那件事、再点完成，成本高到多数人会
 * 直接划掉通知——提醒就白设了。这里让用户在最外层一步处理掉。
 */
class EventActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(EXTRA_EVENT_ID, 0L)
        val action = intent.getStringExtra(EXTRA_ACTION) ?: return
        if (id <= 0L) return
        val app = context.applicationContext as? MoShuApp ?: return

        val pending = goAsync()
        Thread {
            try {
                kotlinx.coroutines.runBlocking {
                    val dao = app.database.eventDao()
                    val event = dao.byId(id) ?: return@runBlocking
                    val now = System.currentTimeMillis()
                    when (action) {
                        ACTION_DONE -> {
                            // 完成：清掉提醒并标记完成。重复事件只推进到下一次，不算「做完」。
                            EventReminderScheduler.cancel(context, event)
                            if (event.repeatRule == RepeatRule.NONE) {
                                dao.update(event.copy(done = true, updatedAt = now))
                            } else {
                                val next = RepeatRule.nextAfter(
                                    event, now, ZoneId.systemDefault(),
                                )
                                if (next != null) {
                                    val advanced = event.copy(startAt = next, updatedAt = now)
                                    dao.update(advanced)
                                    EventReminderScheduler.schedule(context, advanced, now)
                                }
                            }
                        }
                        ACTION_SNOOZE_HOUR, ACTION_SNOOZE_TOMORROW -> {
                            val minutes = if (action == ACTION_SNOOZE_HOUR) 60 else null
                            val target = if (minutes != null) {
                                now + minutes * 60_000L
                            } else {
                                // 推到明天 09:00：比「+24 小时」更符合直觉——
                                // 晚上收到的提醒推到次日晚同一时刻，人还在睡。
                                LocalDate.now().plusDays(1).atTime(9, 0)
                                    .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                            }
                            // 用一个临时事件把提醒排到新时刻，不改动事件本身的日期：
                            // 「推迟」是「晚点再提醒我」，不是「这件事改到明天」。
                            EventReminderScheduler.cancel(context, event)
                            scheduleSnooze(context, event, target)
                            val fmt = DateTimeFormatter.ofPattern("M月d日 HH:mm", Locale.CHINA)
                            val whenText = Instant.ofEpochMilli(target).atZone(ZoneId.systemDefault()).format(fmt)
                            Notifications.showEvent(context, event, "已推迟到 $whenText 再提醒")
                        }
                    }
                    Notifications.cancelEvent(context, event)
                }
            } catch (_: Throwable) {
                // 动作失败不该让接收器崩溃；用户仍可从应用内处理。
            } finally {
                pending.finish()
            }
        }.apply { isDaemon = true }.start()
    }

    companion object {
        const val EXTRA_EVENT_ID = "event_id"
        const val EXTRA_ACTION = "action"
        const val ACTION_DONE = "done"
        const val ACTION_SNOOZE_HOUR = "snooze_hour"
        const val ACTION_SNOOZE_TOMORROW = "snooze_tomorrow"

        /** 推迟用的临时提醒：用独立 requestCode 段，避免和正式排期互相覆盖。 */
        fun snoozePendingIntent(context: Context, event: EventEntity): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                SNOOZE_REQUEST_BASE + event.id.toInt(),
                Intent(context, EventReminderReceiver::class.java).apply {
                    putExtra(EventReminderReceiver.EXTRA_EVENT_ID, event.id)
                    putExtra(EventReminderReceiver.EXTRA_IS_SNOOZE, true)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        private const val SNOOZE_REQUEST_BASE = 60_000

        private fun scheduleSnooze(context: Context, event: EventEntity, at: Long) {
            val alarm = context.getSystemService(android.app.AlarmManager::class.java) ?: return
            runCatching {
                if (EventReminderScheduler.canScheduleExact(context)) {
                    alarm.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, at, snoozePendingIntent(context, event))
                } else {
                    alarm.setWindow(android.app.AlarmManager.RTC_WAKEUP, at, 10 * 60_000L, snoozePendingIntent(context, event))
                }
            }
        }
    }
}
