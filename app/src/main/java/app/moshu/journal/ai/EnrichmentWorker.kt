package app.moshu.journal.ai

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
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
import app.moshu.journal.MainActivity
import app.moshu.journal.MoShuApp
import app.moshu.journal.R
import app.moshu.journal.data.db.EntryAiState
import app.moshu.journal.data.db.EntryDao
import app.moshu.journal.data.db.ManualMetadata
import app.moshu.journal.data.db.TodoEntity
import app.moshu.journal.data.media.ImageStorage
import app.moshu.journal.reminder.Notifications
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONArray
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
        // 记下这次请求对应的正文：请求发出后用户可能又改过，结果就不能再写回了。
        val contentUsed = entry.content
        val attachments = app.database.attachmentDao().forEntry(entryId)
        val images = if (config.visionEnabled) attachments.mapNotNull { ImageStorage.toAiInput(it) } else emptyList()
        val knownTags = runCatching { Enricher.topTags(dao.allTagsJson()) }.getOrDefault(emptyList())

        var (requestResult, enrichment) = Enricher.enrich(config, contentUsed, images, knownTags)
        if (enrichment == null && images.isNotEmpty()) {
            val fallback = Enricher.enrich(config.copy(allowImageAnalysis = false), contentUsed, knownTags = knownTags)
            requestResult = fallback.first
            enrichment = fallback.second
        }
        // 输出被 max_tokens 截断时，用更大的预算立刻重来一次，而不是把「半个 JSON」判成永久失败。
        // AiClient 把 finish_reason=length 映射成可重试的 Fail（消息含「长度限制」），
        // 这里同时兼容将来直接回传 Ok.finishReason 的写法。
        val truncated = (requestResult as? AiClient.Result.Fail)?.message?.contains("长度限制") == true ||
            (requestResult as? AiClient.Result.Ok)?.finishReason == "length"
        if (enrichment == null && truncated) {
            dao.updateAiState(entryId, EntryAiState.PENDING.value, LENGTH_RETRY_MESSAGE)
            val retry = Enricher.enrich(config, contentUsed, images, knownTags, maxTokens = ENRICH_RETRY_MAX_TOKENS)
            requestResult = retry.first
            enrichment = retry.second
        }

        // 每次真实调用都计入用量：BYOK 用户花的是自己的钱，
        // 设置页要能显示「本月约 X 次调用」。
        (requestResult as? AiClient.Result.Ok)?.let {
            runCatching { app.settings.recordAiUsage(it.promptTokens, it.completionTokens) }
        }

        if (enrichment == null) {
            val message = (requestResult as? AiClient.Result.Fail)?.message ?: "返回格式无法识别"
            // 解析失败（Result.Ok 但 JSON 残缺）同样值得重试：修复调用都救不回来的输出，
            // 下一次采样很可能就好了；原先一律判死会让一条 95% 正确的记录永久失败。
            val retryable = when (requestResult) {
                is AiClient.Result.Ok -> true
                is AiClient.Result.Fail -> requestResult.retryable
            }
            if (retryable && runAttemptCount < MAX_ATTEMPTS) {
                // 只是等待重试，不是失败：清空 aiError，界面才会显示「整理中」而不是失败卡片。
                dao.updateAiState(entryId, EntryAiState.PENDING.value)
                return Result.retry()
            }
            dao.updateAiState(entryId, EntryAiState.FAILED.value, message)
            notifyFailures(app, dao)
            return Result.success()
        }

        app.database.withTransaction {
            val latest = dao.byId(entryId) ?: return@withTransaction
            // 正文在请求途中被改过：这份结果描述的是旧文案，写进去只会让概括与正文对不上。
            // 新的 REPLACE 任务会带着新正文重跑。
            if (latest.content != contentUsed) return@withTransaction
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
                    // 记下产出这份元数据的模型与提示词版本，设置页才能找出需要重做的条目。
                    aiModel = config.normalized().model,
                    aiPromptVersion = Enricher.PROMPT_VERSION,
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
                            dueEpochDay = Enricher.parseDue(draft.due),
                            // AI 提取的行动项先标为建议，界面据此提示用户确认，不再静默塞进「行动」。
                            isAiSuggested = true,
                        )
                    )
                }
            }
        }
        return Result.success()
    }

    /** 整理失败汇总通知：多条失败只占一个通知槽，点按直接进「记忆」页的失败筛选。 */
    private suspend fun notifyFailures(context: Context, dao: EntryDao) {
        val count = runCatching { dao.countFailed() }.getOrNull() ?: return
        if (count <= 0) return
        Notifications.ensureAiChannel(context)
        val pending = PendingIntent.getActivity(
            context,
            FAILURE_NOTIFICATION_ID,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                // 失败的条目在「记忆」页，不在「行动」页；带错 extra 会把用户送到一个
                // 根本看不到失败记录的列表上。
                putExtra(MainActivity.EXTRA_OPEN_AI_FAILURES, true)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = "$count 条记录的 AI 整理失败了，点按查看并重试。"
        val notification = NotificationCompat.Builder(context, Notifications.CHANNEL_AI)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("有些记录还没整理好")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java)?.notify(FAILURE_NOTIFICATION_ID, notification)
    }

    companion object {
        private const val KEY_ENTRY_ID = "entry_id"

        /** 5 次重试配合指数退避约可扛过 5 分钟的服务端抖动，避免一次故障就永久判死。 */
        private const val MAX_ATTEMPTS = 5
        private const val ENRICH_RETRY_MAX_TOKENS = 1600
        private const val FAILURE_NOTIFICATION_ID = 3001
        private const val LENGTH_RETRY_MESSAGE = "回答被长度限制截断，已自动重试"

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