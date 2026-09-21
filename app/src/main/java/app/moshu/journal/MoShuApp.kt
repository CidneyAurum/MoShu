package app.moshu.journal

import android.app.Application
import app.moshu.journal.ai.EnrichmentWorker
import app.moshu.journal.data.db.AppDatabase
import app.moshu.journal.data.db.EntryAiState
import app.moshu.journal.data.JournalRepository
import app.moshu.journal.data.settings.SettingsRepository
import app.moshu.journal.reminder.Notifications
import app.moshu.journal.reminder.ReminderScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MoShuApp : Application() {

    lateinit var database: AppDatabase
        private set
    lateinit var settings: SettingsRepository
        private set
    lateinit var journal: JournalRepository
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        database = AppDatabase.build(this)
        settings = SettingsRepository(applicationContext)
        journal = JournalRepository(applicationContext, database, settings)
        Notifications.ensureChannel(this)
        recoverInterruptedEnrichments()
        restoreReminders()
    }

    /**
     * 通知渠道可能被用户清理或系统重建，此时自定义提示音会丢，而设置页仍显示原来的名字；
     * 另外闹钟在系统侧也可能被丢弃。启动时按已保存的偏好重新落地一次。
     */
    private fun restoreReminders() {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching {
                Notifications.applyChannelSound(this@MoShuApp, settings.reminderSound.first())
                if (settings.reminderEnabled.first()) {
                    val (hour, minute) = settings.reminderTime.first()
                    ReminderScheduler.schedule(this@MoShuApp, hour, minute)
                }
            }
        }
    }

    /**
     * 进程在 AI 整理途中被杀时，条目的 aiState 会停在 running，界面上就永远挂着
     * 「整理中」。启动时把这些残留状态退回 pending，能整理的重新入队。
     */
    private fun recoverInterruptedEnrichments() {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val dao = database.entryDao()
            val stale = dao.allOnce().filter { it.aiState == EntryAiState.RUNNING.value }
            if (stale.isEmpty()) return@launch
            val configured = settings.currentAiConfig().valid
            stale.forEach { entry ->
                if (configured && entry.content.isNotBlank()) {
                    dao.updateAiState(entry.id, EntryAiState.PENDING.value)
                    EnrichmentWorker.enqueue(this@MoShuApp, entry.id)
                } else {
                    dao.updateAiState(entry.id, EntryAiState.IDLE.value)
                }
            }
        }
    }

    companion object {
        lateinit var instance: MoShuApp
            private set
    }
}
