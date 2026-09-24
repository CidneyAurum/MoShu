package app.moshu.journal.reminder

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.net.Uri
import androidx.core.app.NotificationCompat
import app.moshu.journal.MainActivity
import app.moshu.journal.R
import app.moshu.journal.data.db.EventEntity
import app.moshu.journal.data.db.TodoEntity

object Notifications {

    const val CHANNEL_REMINDER = "journal_reminder"
    /**
     * AI 整理结果单独一个渠道。与每日提醒共用渠道会出现「把提醒设成静音后，
     * 整理失败通知也被一起静音」这种悄悄丢失重要提示的情况。
     */
    const val CHANNEL_AI = "journal_ai"
    const val SOUND_DEFAULT = ""
    const val SOUND_SILENT = "silent"
    private const val REMINDER_NOTIFICATION_ID = 1001

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_REMINDER) != null) return
        manager.createNotificationChannel(buildChannel(context, SOUND_DEFAULT))
    }

    fun ensureAiChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_AI) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_AI, "AI 整理", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "记录整理完成或失败时的提示"
            }
        )
    }

    /**
     * 应用铃声设置。Android 渠道创建后声音不可修改，
     * 因此切换铃声时删除并重建同名渠道（系统已知怪癖）。
     */
    fun applyChannelSound(context: Context, mode: String) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.deleteNotificationChannel(CHANNEL_REMINDER)
        manager.createNotificationChannel(buildChannel(context, mode))
    }

    fun soundLabel(context: Context, mode: String): String = when (mode) {
        SOUND_SILENT -> "静音"
        "" -> "系统默认"
        else -> {
            val name = runCatching {
                context.contentResolver.query(Uri.parse(mode), null, null, null, null)?.use { cursor ->
                    val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
                }
            }.getOrNull()
            name ?: "自定义铃声"
        }
    }

    private fun buildChannel(context: Context, soundMode: String): NotificationChannel {
        return NotificationChannel(CHANNEL_REMINDER, "日志提醒", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "每日定时提醒你写一笔"
            when (soundMode) {
                SOUND_SILENT -> setSound(null, null)
                SOUND_DEFAULT -> {} // 跟随渠道默认
                else -> runCatching {
                    val uri = Uri.parse(soundMode)
                    setSound(
                        uri,
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                }
            }
        }
    }

    // ---------- 自定义事件：每个事件可以有自己的铃声 ----------

    /** 事件通知的基础渠道（未指定铃声时使用）。 */
    const val CHANNEL_EVENT = "journal_event"

    /**
     * 事件渠道 id 由「铃声 + 重要级」决定，而不是一个事件一个渠道。
     *
     * 原因是 Android 的硬约束：**渠道的声音在创建后不可修改**。想给每个事件不同铃声，
     * 只能让铃声不同的通知走不同渠道。按铃声去重而不是按事件去重，
     * 是因为「一个事件一个渠道」会让渠道列表随事件数无限膨胀，
     * 而铃声种类通常只有个位数——同铃声的事件共用一个渠道，行为完全一致。
     */
    fun eventChannelId(soundUri: String, importance: String): String {
        if (soundUri.isEmpty() || soundUri == SOUND_SILENT) return CHANNEL_EVENT
        return "journal_event_${importance}_${soundUri.hashCode().toUInt().toString(16)}"
    }

    /**
     * 确保事件所需渠道存在。返回该事件应使用的渠道 id。
     *
     * 渠道名带上铃声名，用户在系统设置里能看出「哪个渠道是哪个铃声」，
     * 而不是面对一串哈希。
     */
    fun ensureEventChannel(context: Context, soundUri: String, soundLabel: String, importance: String): String {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return CHANNEL_EVENT
        val id = eventChannelId(soundUri, importance)
        if (manager.getNotificationChannel(id) != null) return id
        val level = if (importance == "high") NotificationManager.IMPORTANCE_HIGH else NotificationManager.IMPORTANCE_DEFAULT
        val channel = NotificationChannel(
            id,
            if (soundUri.isEmpty()) "事件提醒" else "事件提醒 · ${soundLabel.ifBlank { "自定义铃声" }}",
            level,
        ).apply {
            description = "日历事件的定时提醒"
            when (soundUri) {
                SOUND_SILENT -> setSound(null, null)
                "" -> {} // 跟随渠道默认
                else -> runCatching {
                    setSound(
                        Uri.parse(soundUri),
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build(),
                    )
                }
            }
        }
        manager.createNotificationChannel(channel)
        return id
    }

    /** 事件提醒通知。每个事件一个通知槽，互不覆盖。 */
    fun showEvent(context: Context, event: EventEntity, body: String) {
        val channelId = ensureEventChannel(context, event.soundUri, event.soundLabel, event.importance)
        val notificationId = 30_000 + event.id.toInt()
        val pending = PendingIntent.getActivity(
            context,
            notificationId,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(EXTRA_OPEN_EVENT_ID, event.id)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val whenText = runCatching {
            java.text.SimpleDateFormat("M月d日 HH:mm", java.util.Locale.CHINA)
                .format(java.util.Date(event.startAt))
        }.getOrDefault("")
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(event.title)
            .setContentText(body.ifBlank { whenText })
            .setStyle(NotificationCompat.BigTextStyle().bigText(body.ifBlank { whenText }))
            .setContentIntent(pending)
            .setAutoCancel(true)
            // 通知上直接处理：打开应用再找那件事的成本太高，多数人会直接划掉通知。
            .addAction(0, "完成", eventAction(context, event, EventActionReceiver.ACTION_DONE, notificationId))
            .addAction(0, "1 小时后", eventAction(context, event, EventActionReceiver.ACTION_SNOOZE_HOUR, notificationId + 1))
            .addAction(0, "明天 9 点", eventAction(context, event, EventActionReceiver.ACTION_SNOOZE_TOMORROW, notificationId + 2))
            .apply { if (event.importance == "high") setPriority(NotificationCompat.PRIORITY_HIGH) }
            .build()
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.notify(notificationId, notification)
    }

    /** 点击事件通知后要打开的条目。 */
    const val EXTRA_OPEN_EVENT_ID = "app.moshu.journal.OPEN_EVENT_ID"

    /** 事件通知上的动作按钮。requestCode 必须互不相同，否则三个按钮会共用一个 PendingIntent。 */
    private fun eventAction(context: Context, event: EventEntity, action: String, requestCode: Int): android.app.PendingIntent =
        android.app.PendingIntent.getBroadcast(
            context,
            requestCode,
            Intent(context, EventActionReceiver::class.java).apply {
                putExtra(EventActionReceiver.EXTRA_EVENT_ID, event.id)
                putExtra(EventActionReceiver.EXTRA_ACTION, action)
            },
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )

    /** 用户已在通知上处理过（完成/推迟）时，把原通知撤掉，避免它继续挂着。 */
    fun cancelEvent(context: Context, event: EventEntity) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        runCatching { manager.cancel(30_000 + event.id.toInt()) }
    }

    fun showReminder(context: Context, text: String) {
        ensureChannel(context)
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pending = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_REMINDER)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("墨枢")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.notify(REMINDER_NOTIFICATION_ID, notification)
    }

    fun showTodo(context: Context, todo: TodoEntity) {
        ensureChannel(context)
        // 通知 id 与 PendingIntent 的 requestCode 用同一个值。原先 id 取模 10000，
        // 会让相差 10000 的两条待办共用同一个通知槽，后到的会顶掉先到的。
        val notificationId = 20_000 + todo.id.toInt()
        val pending = PendingIntent.getActivity(
            context,
            notificationId,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(MainActivity.EXTRA_OPEN_ACTIONS, true)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_REMINDER)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("该行动了")
            .setContentText(todo.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(todo.text))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.notify(notificationId, notification)
    }
}
