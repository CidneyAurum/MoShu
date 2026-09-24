package app.moshu.journal.data.backup

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.moshu.journal.MoShuApp
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * 每天一次的本机自动备份。
 *
 * 用 WorkManager 的周期任务而不是 AlarmManager：备份晚一小时完全无所谓，
 * 但绝不能在电量低或存储吃紧时硬来，这些约束正好由 WorkManager 兜住。
 */
class AutoBackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = MoShuApp.instance
        // 用户可能在任务已排队之后关掉了开关，这时必须再查一次，否则关掉还会继续备份。
        if (!app.settings.autoBackupEnabled.first()) return Result.success()
        return try {
            AutoBackup.runOnce(applicationContext, app.database)
            Result.success()
        } catch (_: Exception) {
            // 不弹通知：一次备份失败不值得打扰用户，下一次周期任务会继续试。
            if (runAttemptCount < 2) Result.retry() else Result.failure()
        }
    }

    companion object {
        private const val PERIODIC_NAME = "moshu_auto_backup"
        private const val MANUAL_NAME = "moshu_auto_backup_now"

        /** 按开关排期或取消。开关打开时用 [ExistingPeriodicWorkPolicy.KEEP]，不重置已有的计时。 */
        fun sync(context: Context, enabled: Boolean) {
            val work = WorkManager.getInstance(context)
            if (!enabled) {
                work.cancelUniqueWork(PERIODIC_NAME)
                return
            }
            val request = PeriodicWorkRequestBuilder<AutoBackupWorker>(1, TimeUnit.DAYS)
                .setConstraints(
                    // 只要「电量不算低」。不要求充电：备份可能带着全部图片，
                    // 但要求充电会让「昨天刚开的开关」到第二天晚上都还看不到第一份备份。
                    Constraints.Builder().setRequiresBatteryNotLow(true).build(),
                )
                .build()
            work.enqueueUniquePeriodicWork(PERIODIC_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        /** 用户点「立即备份一次」。 */
        fun runNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<AutoBackupWorker>().build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(MANUAL_NAME, ExistingWorkPolicy.REPLACE, request)
        }
    }
}