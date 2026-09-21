package app.moshu.journal.ai

import android.content.Context
import androidx.room.withTransaction
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.moshu.journal.MoShuApp
import app.moshu.journal.data.db.EntryAiState
import app.moshu.journal.data.db.ManualMetadata
import app.moshu.journal.data.db.TodoEntity
import app.moshu.journal.data.media.ImageStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

class EnrichmentWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val entryId = inputData.getLong(KEY_ENTRY_ID, 0)
        if (entryId <= 0) return Result.failure()
        return try {
            runEnrichment(entryId)
        } catch (cancel: CancellationException) {
            // 任务被取消（例如编辑后用 REPLACE 顶掉旧任务）时把 running 退回 pending，
            // 否则条目会永远显示「整理中」。CoroutineWorker.onStopped 是 final 的，
            // 只能在取消路径上自己收尾。
            withContext(NonCancellable) { resetIfRunning(entryId) }
            throw cancel
        }
    }

    private suspend fun resetIfRunning(entryId: Long) {
        val dao = (applicationContext as MoShuApp).database.entryDao()
        val entry = dao.byId(entryId) ?: return
        if (entry.aiState == EntryAiState.RUNNING.value) {
            dao.updateAiState(entryId, EntryAiState.PENDING.value)
        }
    }

    private suspend fun runEnrichment(entryId: Long): Result {
        val app = applicationContext as MoShuApp
        val dao = app.database.entryDao()
        val entry = dao.byId(entryId) ?: return Result.success()
        val config = app.settings.currentAiConfig()
        if (!config.valid) {
            dao.updateAiState(entryId, EntryAiState.IDLE.value)
            return Result.success()
        }

        dao.updateAiState(entryId, EntryAiState.RUNNING.value)
        val attachments = app.database.attachmentDao().forEntry(entryId)
        val images = if (config.visionEnabled) attachments.mapNotNull { ImageStorage.toAiInput(it) } else emptyList()
        var (requestResult, enrichment) = Enricher.enrich(config, entry.content, images)
        if (enrichment == null && images.isNotEmpty()) {
            val fallback = Enricher.enrich(config.copy(allowImageAnalysis = false), entry.content)
            requestResult = fallback.first
            enrichment = fallback.second
        }

        if (enrichment == null) {
            val message = (requestResult as? AiClient.Result.Fail)?.message ?: "返回格式无法识别"
            val retryable = (requestResult as? AiClient.Result.Fail)?.retryable == true
            if (retryable && runAttemptCount < 2) {
                dao.updateAiState(entryId, EntryAiState.PENDING.value, "稍后自动重试")
                return Result.retry()
            }
            dao.updateAiState(entryId, EntryAiState.FAILED.value, message)
            return Result.success()
        }

        app.database.withTransaction {
            val latest = dao.byId(entryId) ?: return@withTransaction
            val mask = latest.manualMetadataMask
            dao.update(
                latest.copy(
                    categoryId = if (ManualMetadata.isManual(mask, ManualMetadata.CATEGORY)) latest.categoryId else enrichment.categoryId,
                    tagsJson = if (ManualMetadata.isManual(mask, ManualMetadata.TAGS)) latest.tagsJson else JSONArray(enrichment.tags).toString(),
                    summary = if (ManualMetadata.isManual(mask, ManualMetadata.SUMMARY)) latest.summary else enrichment.summary,
                    mood = if (ManualMetadata.isManual(mask, ManualMetadata.MOOD)) latest.mood else enrichment.mood,
                    enriched = true,
                    aiState = EntryAiState.SUCCEEDED.value,
                    aiError = "",
                    updatedAt = System.currentTimeMillis(),
                )
            )
            val todoDao = app.database.todoDao()
            val preserved = todoDao.bySource(entryId).filter { it.userEdited || it.isUserCreated }
            todoDao.deleteUneditedBySource(entryId)
            enrichment.todos.forEach { draft ->
                if (preserved.none { it.text.trim().equals(draft.text.trim(), ignoreCase = true) }) {
                    todoDao.insert(
                        TodoEntity(
                            text = draft.text,
                            sourceEntryId = entryId,
                            createdAt = System.currentTimeMillis(),
                            dueEpochDay = parseDue(draft.due),
                        )
                    )
                }
            }
        }
        return Result.success()
    }

    private fun parseDue(value: String?): Int? = value?.trim()?.takeIf { it.isNotEmpty() }?.let {
        runCatching { LocalDate.parse(it, DateTimeFormatter.ISO_LOCAL_DATE).toEpochDay().toInt() }.getOrNull()
    }

    companion object {
        private const val KEY_ENTRY_ID = "entry_id"

        fun enqueue(context: Context, entryId: Long) {
            val request = OneTimeWorkRequestBuilder<EnrichmentWorker>()
                .setInputData(Data.Builder().putLong(KEY_ENTRY_ID, entryId).build())
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "enrich_entry_$entryId",
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }
    }
}
