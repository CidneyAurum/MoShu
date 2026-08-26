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

        fun schedule(context: Context, todo: TodoEntity) {
            val at = todo.reminderAt ?: return cancel(context, todo.uid)
            val delay = (at - System.currentTimeMillis()).coerceAtLeast(0)
            val request = OneTimeWorkRequestBuilder<TodoReminderWorker>()
                .setInputData(Data.Builder().putLong(KEY_TODO_ID, todo.id).build())
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("todo_reminder_${todo.uid}", ExistingWorkPolicy.REPLACE, request)
        }

        fun cancel(context: Context, uid: String) {
            WorkManager.getInstance(context).cancelUniqueWork("todo_reminder_$uid")
        }
    }
}
