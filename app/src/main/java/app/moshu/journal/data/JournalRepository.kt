package app.moshu.journal.data

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import app.moshu.journal.ai.Enricher
import app.moshu.journal.ai.EnrichmentWorker
import app.moshu.journal.data.db.AppDatabase
import app.moshu.journal.data.db.AttachmentEntity
import app.moshu.journal.data.db.Category
import app.moshu.journal.data.db.EntryAiState
import app.moshu.journal.data.db.EntryDao
import app.moshu.journal.data.db.EntryEntity
import app.moshu.journal.data.db.ManualMetadata
import app.moshu.journal.data.db.TodoEntity
import app.moshu.journal.data.media.ImageStorage
import app.moshu.journal.data.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** reEnrich 的结果。调用方据此给出反馈，否则「重新整理」看起来像按了没反应。 */
enum class ReEnrichResult { Enriched, NotConfigured, EmptyContent, AllManual }

/**
 * 一次可撤销的删除快照。附件文件在撤销窗口结束前不会被删，所以撤销能完整还原；
 * 待办分两类：随记忆一起删掉的，以及只是被摘掉来源链接、本身仍在的。
 */
data class DeletedEntry(
    val entry: EntryEntity,
    val removedTodos: List<TodoEntity>,
    val detachedTodos: List<TodoEntity>,
    val attachments: List<AttachmentEntity>,
)

class JournalRepository(
    private val context: Context,
    private val db: AppDatabase,
    private val settings: SettingsRepository,
) {
    val pendingCount: Flow<Int> = db.entryDao().observePendingCount()

    /**
     * 延迟清理附件文件的后台作用域。进程被杀时这里挂起的任务会一起消失，
     * 于是文件成为孤儿——MoShuApp 启动时会做一次孤儿清理收口。
     */
    private val purgeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val purgeJobs = ConcurrentHashMap<Long, Job>()
    private val attachmentPurgeJobs = ConcurrentHashMap<Long, Job>()

    /**
     * 只订阅给定条目的附件。原先列表页直接 observeAll()，任何一张图片的增删都会
     * 重新发射并重新分组整张附件表，开销随整个日记本增长而不是随可见条目增长。
     */
    fun observeAttachments(entryIds: List<Long>): Flow<List<AttachmentEntity>> =
        if (entryIds.isEmpty()) flowOf(emptyList()) else db.attachmentDao().observeForEntries(entryIds)

    fun observe(category: Int?, query: String): Flow<List<EntryEntity>> {
        val dao = db.entryDao()
        return when {
            query.isNotBlank() -> searchFlow(dao, query)
            category != null -> dao.observeByCategory(category)
            else -> dao.observeAll()
        }
    }

    /**
     * 中文（及日韩）查询走 LIKE 子串匹配，其余走 FTS 前缀索引。
     *
     * FTS4 的默认分词器把连续的 CJK 字符视为一个 token，「今天加班到十点」整句是一个词，
     * 于是搜「加班」永远匹配不到——而且是静默返回空，用户只会以为没记过。
     * MATCH 的求值发生在收集期，所以回退也必须写在流内部。
     */
    private fun searchFlow(dao: EntryDao, query: String): Flow<List<EntryEntity>> {
        val trimmed = query.trim()
        if (containsCjk(trimmed)) return dao.searchLike(escapeLike(trimmed))
        return flow { emitAll(dao.searchFts(toFtsQuery(trimmed))) }
            .catch { emitAll(dao.searchLike(escapeLike(trimmed))) }
    }

    suspend fun addEntry(content: String, images: List<Uri> = emptyList()): Long {
        val trimmed = content.trim()
        if (trimmed.isEmpty() && images.isEmpty()) return 0
        val config = settings.currentAiConfig()
        val now = System.currentTimeMillis()
        val id = db.entryDao().insert(
            EntryEntity(
                content = trimmed,
                createdAt = now,
                updatedAt = now,
                aiState = if (config.valid && trimmed.isNotEmpty()) EntryAiState.PENDING.value else EntryAiState.IDLE.value,
            )
        )
        try {
            images.take(ImageStorage.MAX_ATTACHMENTS).forEachIndexed { index, uri ->
                val attachment = ImageStorage.importImage(context, uri, id, index)
                db.attachmentDao().insert(attachment)
            }
        } catch (error: Throwable) {
            db.attachmentDao().forEntry(id).forEach(ImageStorage::delete)
            db.entryDao().deleteById(id)
            throw error
        }
        if (config.valid && trimmed.isNotEmpty()) EnrichmentWorker.enqueue(context, id)
        return id
    }

    suspend fun updateEntry(
        entry: EntryEntity,
        content: String,
        categoryId: Int,
        tags: List<String>,
        mood: String,
        summary: String,
    ) {
        val trimmedContent = content.trim()
        val contentChanged = trimmedContent != entry.content
        val aiConfigured = settings.currentAiConfig().valid
        val safeCategory = categoryId.coerceIn(Category.LIFE, Category.IDEA)
        val safeTags = tags.map { it.trim() }.filter { it.isNotEmpty() }.take(6)
        val safeSummary = summary.trim().take(60)
        // 只冻结用户真正改动的字段。原先无条件 OR 上四个位，用户改一个错别字
        // 就会把分类/标签/情绪/概括永久锁死，AI 再也更新不了。
        val mask = entry.manualMetadataMask or
            Enricher.changedFields(entry, trimmedContent, safeCategory, safeTags, mood, safeSummary)
        val updated = entry.copy(
            content = trimmedContent,
            categoryId = safeCategory,
            tagsJson = JSONArray(safeTags).toString(),
            mood = mood,
            summary = safeSummary,
            manualMetadataMask = mask,
            updatedAt = System.currentTimeMillis(),
            aiState = if (contentChanged && aiConfigured) EntryAiState.PENDING.value else if (contentChanged) EntryAiState.IDLE.value else entry.aiState,
            aiError = if (contentChanged) "" else entry.aiError,
        )
        db.entryDao().update(updated)
        if (contentChanged && aiConfigured) EnrichmentWorker.enqueue(context, entry.id)
    }

    suspend fun togglePinned(entry: EntryEntity) {
        db.entryDao().update(entry.copy(isPinned = !entry.isPinned, updatedAt = System.currentTimeMillis()))
    }

    suspend fun reEnrich(entryId: Long): ReEnrichResult {
        val entry = db.entryDao().byId(entryId) ?: return ReEnrichResult.EmptyContent
        if (!settings.currentAiConfig().valid) return ReEnrichResult.NotConfigured
        if (entry.content.isBlank()) return ReEnrichResult.EmptyContent
        db.entryDao().updateAiState(entryId, EntryAiState.PENDING.value)
        EnrichmentWorker.enqueue(context, entryId)
        // 四个元数据字段都被用户手动接管时，这次整理只会更新行动项，需要如实告诉用户。
        val mask = entry.manualMetadataMask
        val allManual = ManualMetadata.isManual(mask, ManualMetadata.CATEGORY) &&
            ManualMetadata.isManual(mask, ManualMetadata.TAGS) &&
            ManualMetadata.isManual(mask, ManualMetadata.MOOD) &&
            ManualMetadata.isManual(mask, ManualMetadata.SUMMARY)
        return if (allManual) ReEnrichResult.AllManual else ReEnrichResult.Enriched
    }

    /** 清除指定的人工标记位（mask 为要清除的位），让 AI 重新接管这些字段。 */
    suspend fun clearManualMetadata(entry: EntryEntity, mask: Int) {
        if (mask == 0) return
        db.entryDao().update(
            entry.copy(
                manualMetadataMask = entry.manualMetadataMask and mask.inv(),
                updatedAt = System.currentTimeMillis(),
            )
        )
    }

    /** 采纳 AI 建议的行动：转为用户自有行动，后续整理不会再把它当作可丢弃的自动提取项。 */
    suspend fun acceptAiTodo(todo: TodoEntity) {
        db.todoDao().update(todo.copy(isAiSuggested = false, isUserCreated = true, updatedAt = System.currentTimeMillis()))
    }

    /** 忽略 AI 建议的行动：直接删除，避免它继续出现在「行动」里。 */
    suspend fun dismissAiTodo(todo: TodoEntity) {
        db.todoDao().deleteById(todo.id)
    }

    suspend fun addImages(entryId: Long, uris: List<Uri>) {
        val existing = db.attachmentDao().forEntry(entryId)
        val accepted = uris.take((ImageStorage.MAX_ATTACHMENTS - existing.size).coerceAtLeast(0))
        // 已经满了就不要再触发一次 AI 整理（那是一次真实计费的调用）。
        if (accepted.isEmpty()) return
        val inserted = mutableListOf<AttachmentEntity>()
        try {
            accepted.forEachIndexed { index, uri ->
                val attachment = ImageStorage.importImage(context, uri, entryId, existing.size + index)
                db.attachmentDao().insert(attachment)
                inserted += attachment
            }
        } catch (error: Throwable) {
            // 与 addEntry 一致：中途失败要把本次写入的行和磁盘文件都清掉，不留孤儿。
            inserted.forEach { attachment ->
                ImageStorage.delete(attachment)
                db.attachmentDao().deleteById(attachment.id)
            }
            throw error
        }
        reEnrich(entryId)
    }

    /** 复制一条记忆（不含图片）。返回新条目 id，用于跳转或提示。 */
    suspend fun duplicateEntry(entryId: Long): Long? {
        val entry = db.entryDao().byId(entryId) ?: return null
        val config = settings.currentAiConfig()
        val now = System.currentTimeMillis()
        // 不复制图片：同一份文件被两个条目引用时，删任意一条都会让另一条变成破图。
        val copy = entry.copy(
            id = 0,
            // uid 有唯一索引，副本必须换一个，否则插入直接失败。
            uid = UUID.randomUUID().toString(),
            createdAt = now,
            updatedAt = now,
            isPinned = false,
            enriched = false,
            aiState = if (config.valid && entry.content.isNotBlank()) EntryAiState.PENDING.value else EntryAiState.IDLE.value,
            aiError = "",
            aiModel = "",
            aiPromptVersion = 0,
        )
        val id = db.entryDao().insert(copy)
        if (config.valid && entry.content.isNotBlank()) EnrichmentWorker.enqueue(context, id)
        return id
    }

    /**
     * 可撤销删除：只删数据库行，附件文件延后到撤销窗口结束再删。
     * 返回快照供 [restoreEntry] 使用。
     */
    suspend fun deleteEntry(entryId: Long): DeletedEntry? {
        val entry = db.entryDao().byId(entryId) ?: return null
        val attachments = db.attachmentDao().forEntry(entryId)
        val todos = db.todoDao().bySource(entryId)
        val removed = todos.filterNot { it.isUserCreated || it.userEdited }
        val detached = todos.filter { it.isUserCreated || it.userEdited }
        db.withTransaction {
            // AI 提取且用户没动过的行动随记忆一起消失；用户自建或改过的留下，
            // 但必须摘掉 sourceEntryId，否则「来自记忆」会指向一条已删除的记录。
            detached.forEach { db.todoDao().update(it.copy(sourceEntryId = 0, updatedAt = System.currentTimeMillis())) }
            removed.forEach { db.todoDao().deleteById(it.id) }
            db.entryDao().deleteById(entryId)
        }
        scheduleEntryPurge(entryId, attachments)
        return DeletedEntry(entry, removed, detached, attachments)
    }

    /** 撤销删除。条目会用原来的 uid 重建，附件行与文件都还在，无需重新导入。 */
    suspend fun restoreEntry(deleted: DeletedEntry): Long {
        purgeJobs.remove(deleted.entry.id)?.cancel()
        return db.withTransaction {
            val newId = db.entryDao().insert(deleted.entry.copy(id = 0))
            deleted.attachments.forEach { db.attachmentDao().insert(it.copy(id = 0, entryId = newId)) }
            deleted.removedTodos.forEach { db.todoDao().insert(it.copy(id = 0, sourceEntryId = newId)) }
            // 被摘掉来源链接的待办仍在表里，按 uid 找回并重新指向恢复后的条目。
            deleted.detachedTodos.forEach { todo ->
                db.todoDao().byUid(todo.uid)?.let {
                    db.todoDao().update(it.copy(sourceEntryId = newId, updatedAt = System.currentTimeMillis()))
                }
            }
            newId
        }
    }

    suspend fun deleteAttachment(attachmentId: Long, entryId: Long): AttachmentEntity? {
        val attachment = db.attachmentDao().forEntry(entryId).firstOrNull { it.id == attachmentId } ?: return null
        db.attachmentDao().deleteById(attachmentId)
        scheduleAttachmentPurge(attachment)
        return attachment
    }

    suspend fun restoreAttachment(attachment: AttachmentEntity) {
        attachmentPurgeJobs.remove(attachment.id)?.cancel()
        db.attachmentDao().insert(attachment.copy(id = 0))
    }

    /** 撤销窗口：够用户看清提示并点一下，又不至于让磁盘文件长时间悬空。 */
    private fun scheduleEntryPurge(entryId: Long, attachments: List<AttachmentEntity>) {
        if (attachments.isEmpty()) return
        purgeJobs.remove(entryId)?.cancel()
        purgeJobs[entryId] = purgeScope.launch {
            delay(UNDO_WINDOW_MS)
            attachments.forEach(ImageStorage::delete)
            purgeJobs.remove(entryId)
        }
    }

    private fun scheduleAttachmentPurge(attachment: AttachmentEntity) {
        attachmentPurgeJobs.remove(attachment.id)?.cancel()
        attachmentPurgeJobs[attachment.id] = purgeScope.launch {
            delay(UNDO_WINDOW_MS)
            ImageStorage.delete(attachment)
            attachmentPurgeJobs.remove(attachment.id)
        }
    }

    private fun toFtsQuery(raw: String): String {
        val terms = raw.trim().split(Regex("\\s+")).map { it.replace(Regex("[^\\p{L}\\p{N}_-]"), "") }.filter { it.isNotBlank() }
        return terms.joinToString(" AND ") { "\"$it\"*" }.ifBlank { "\"${raw.trim().replace("\"", "")}\"" }
    }

    companion object {
        /** 删除后的撤销窗口。 */
        const val UNDO_WINDOW_MS = 8_000L

        /** 中日韩字符判定。含这类字符的查询必须走 LIKE 子串匹配，FTS 的按词索引匹配不到句中词。 */
        fun containsCjk(value: String): Boolean = value.any { char ->
            val code = char.code
            code in 0x2E80..0x9FFF || code in 0xF900..0xFAFF || code in 0x3040..0x30FF || code in 0xAC00..0xD7AF
        }

        /** 转义 LIKE 的通配符，否则搜「%」会返回整个日记本。与 DAO 的 ESCAPE '!' 配套。 */
        fun escapeLike(value: String): String = value
            .replace("!", "!!")
            .replace("%", "!%")
            .replace("_", "!_")
    }
}
