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
