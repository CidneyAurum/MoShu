package app.moshu.journal.reminder

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.moshu.journal.MoShuApp
import app.moshu.journal.data.db.TodoEntity
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit

class TodoReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getLong(KEY_TODO_ID, 0)
        val todo = (applicationContext as MoShuApp).database.todoDao().byId(id) ?: return Result.success()
        if (!todo.done) Notifications.showTodo(applicationContext, todo)
        return Result.success()
    }

    companion object {
        private const val KEY_TODO_ID = "todo_id"

        /** 待办提醒统一落在截止日当天的本地 09:00；调度与保存必须用同一个常量。 */
        const val REMINDER_HOUR = 9

        fun schedule(context: Context, todo: TodoEntity) {
            // reminderAt 只作为「是否开启提醒」的标记；触发时刻由截止日 + 本地 09:00 现算，
            // 而不是沿用保存时算好的绝对时间戳——后者在用户跨时区后会落到错误的本地时刻。
            if (todo.reminderAt == null) return cancel(context, todo.uid)
            val due = todo.dueEpochDay ?: return cancel(context, todo.uid)
            val at = LocalDate.ofEpochDay(due.toLong()).atTime(REMINDER_HOUR, 0)
                .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val delay = at - System.currentTimeMillis()
            // 提醒时刻已经过去（例如今天 09:00 之后才把截止日设成今天），
            // 不要再排：WorkManager 会把 0 延迟当成「立即执行」，用户刚点保存就被弹通知。
            if (delay <= 0) return cancel(context, todo.uid)
            val request = OneTimeWorkRequestBuilder<TodoReminderWorker>()
                .setInputData(Data.Builder().putLong(KEY_TODO_ID, todo.id).build())
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("todo_reminder_${todo.uid}", ExistingWorkPolicy.REPLACE, request)
        }

        /** 时区或系统时间变化后重排所有待办提醒，使其仍落在本地 09:00。 */
        suspend fun rescheduleAll(context: Context) {
            (context.applicationContext as MoShuApp).database.todoDao().allOnce()
                .filter { !it.done && it.reminderAt != null }
                .forEach { schedule(context, it) }
        }

        fun cancel(context: Context, uid: String) {
            WorkManager.getInstance(context).cancelUniqueWork("todo_reminder_$uid")
        }
    }
}
