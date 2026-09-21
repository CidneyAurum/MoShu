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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import org.json.JSONArray

/** reEnrich 的结果。调用方据此给出反馈，否则「重新整理」看起来像按了没反应。 */
enum class ReEnrichResult { Enriched, NotConfigured, EmptyContent, AllManual }

class JournalRepository(
    private val context: Context,
    private val db: AppDatabase,
    private val settings: SettingsRepository,
) {
    val pendingCount: Flow<Int> = db.entryDao().observePendingCount()

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

    suspend fun deleteAttachment(attachmentId: Long, entryId: Long) {
        val attachment = db.attachmentDao().forEntry(entryId).firstOrNull { it.id == attachmentId } ?: return
        ImageStorage.delete(attachment)
        db.attachmentDao().deleteById(attachmentId)
    }

    suspend fun deleteEntry(entryId: Long) {
        val attachments = db.attachmentDao().forEntry(entryId)
        db.withTransaction {
            // AI 提取且用户没动过的行动随记忆一起消失；用户自己建的或改过的留下，
            // 但必须摘掉 sourceEntryId，否则「来自记忆」会指向一条已删除的记录。
            db.todoDao().bySource(entryId).forEach { todo ->
                if (todo.isUserCreated || todo.userEdited) {
                    db.todoDao().update(todo.copy(sourceEntryId = 0, updatedAt = System.currentTimeMillis()))
                } else {
                    db.todoDao().deleteById(todo.id)
                }
            }
            db.entryDao().deleteById(entryId)
        }
        // 文件删除放在事务提交之后：文件系统不参与事务，先删会让回滚后的数据库指向空文件。
        attachments.forEach(ImageStorage::delete)
    }

    private fun toFtsQuery(raw: String): String {
        val terms = raw.trim().split(Regex("\\s+")).map { it.replace(Regex("[^\\p{L}\\p{N}_-]"), "") }.filter { it.isNotBlank() }
        return terms.joinToString(" AND ") { "\"$it\"*" }.ifBlank { "\"${raw.trim().replace("\"", "")}\"" }
    }

    companion object {
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
