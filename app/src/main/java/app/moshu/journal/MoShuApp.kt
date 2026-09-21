package app.moshu.journal

import android.app.Application
import app.moshu.journal.ai.EnrichmentWorker
import app.moshu.journal.data.NoticeBus
import app.moshu.journal.data.db.AppDatabase
import app.moshu.journal.data.db.EntryAiState
import app.moshu.journal.data.JournalRepository
import app.moshu.journal.data.settings.DraftStore
import app.moshu.journal.data.settings.SettingsRepository
import app.moshu.journal.reminder.Notifications
import app.moshu.journal.reminder.ReminderScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File

class MoShuApp : Application() {

    lateinit var database: AppDatabase
        private set
    lateinit var settings: SettingsRepository
        private set
    lateinit var journal: JournalRepository
        private set
    lateinit var drafts: DraftStore
        private set

    /** 全局提示总线（撤销、AI 完成等），由主界面的一个 snackbar 宿主统一承接。 */
    val notices = NoticeBus()

    override fun onCreate() {
        super.onCreate()
        instance = this
        database = AppDatabase.build(this)
        settings = SettingsRepository(applicationContext)
        drafts = DraftStore(applicationContext)
        journal = JournalRepository(applicationContext, database, settings)
        Notifications.ensureChannel(this)
        Notifications.ensureAiChannel(this)
        cleanupOrphanAttachments()
        recoverInterruptedEnrichments()
        restoreReminders()
    }

    /**
     * 清理没有被任何附件行引用的图片文件。
     * 延迟删除的任务活在内存里，进程在撤销窗口内被杀就会留下孤儿文件。
     */
    private fun cleanupOrphanAttachments() {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching {
                val known = database.attachmentDao().allOnce().map { it.localPath }.toSet()
                File(filesDir, "attachments").listFiles()?.forEach { file ->
                    if (file.isFile && file.absolutePath !in known) file.delete()
                }
            }
        }
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
