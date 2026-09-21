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
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
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

/**
 * 写作统计。全部由本地数据算出：字数按非空白字符计（中文场景比按空格分词更接近直觉）。
 * [busiestHour] 为 0–23，没有记录时为 null。
 */
data class WritingStats(
    val totalEntries: Int = 0,
    val totalChars: Int = 0,
    val averageChars: Int = 0,
    val activeDays: Int = 0,
    val longestStreak: Int = 0,
    val busiestHour: Int? = null,
    val firstAt: Long? = null,
)

/** 一个标签与它最常伴随的情绪。[ratio] 越高说明这个标签的情绪越一致。 */
data class MoodTagLink(
    val tag: String,
    val mood: String,
    val moodCount: Int,
    val total: Int,
) {
    val ratio: Float get() = if (total == 0) 0f else moodCount.toFloat() / total
}

/** 情绪-标签关联至少需要多少条记录才值得展示。 */
private const val MOOD_TAG_MIN_ENTRIES = 3

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
     * 删除一条记忆：**进回收站**，不是立刻销毁。
     *
     * 返回快照供 [restoreEntry] 撤销。附件行与磁盘文件都不动——回收站里的条目仍然完整，
     * 只有「彻底删除」或保留期结束时才真正清理文件。这比原先「8 秒后删文件」安全得多：
     * 误删往往过一会儿才发现，而不是 8 秒内。
     */
    suspend fun deleteEntry(entryId: Long): DeletedEntry? {
        val entry = db.entryDao().byId(entryId) ?: return null
        if (entry.inTrash) return null
        val attachments = db.attachmentDao().forEntry(entryId)
        val todos = db.todoDao().bySource(entryId)
        val now = System.currentTimeMillis()
        // AI 提取且用户没动过的行动随记忆一起消失（它们可由整理重新生成）；
        // 用户自建或改过的留下，且保留 sourceEntryId —— 条目还在回收站里，恢复后链接依然有效。
        val removed = todos.filterNot { it.isUserCreated || it.userEdited }
        val detached = todos.filter { it.isUserCreated || it.userEdited }
        db.withTransaction {
            removed.forEach { db.todoDao().deleteById(it.id) }
            db.entryDao().markDeleted(entryId, now)
        }
        return DeletedEntry(entry, removed, detached, attachments)
    }

    /** 撤销删除。条目行还在（只是被标记），把状态清掉并把随删的 AI 行动补回来。 */
    suspend fun restoreEntry(deleted: DeletedEntry): Long {
        purgeJobs.remove(deleted.entry.id)?.cancel()
        val now = System.currentTimeMillis()
        return db.withTransaction {
            db.entryDao().restoreFromTrash(deleted.entry.id, now)
            deleted.removedTodos.forEach { db.todoDao().insert(it.copy(id = 0, sourceEntryId = deleted.entry.id)) }
            deleted.detachedTodos.forEach { todo ->
                db.todoDao().byUid(todo.uid)?.let {
                    db.todoDao().update(it.copy(sourceEntryId = deleted.entry.id, updatedAt = now))
                }
            }
            deleted.entry.id
        }
    }

    /** 从回收站恢复（不带快照）：只清删除标记，条目与图片立即回到列表。 */
    suspend fun restoreFromTrash(entryId: Long): Boolean {
        val entry = db.entryDao().byId(entryId) ?: return false
        if (!entry.inTrash) return false
        db.entryDao().restoreFromTrash(entryId)
        return true
    }

    /** 彻底删除一条（回收站内）：这次连附件文件一起清掉。 */
    suspend fun purgeEntry(entryId: Long) {
        val attachments = db.attachmentDao().forEntry(entryId)
        val todos = db.todoDao().bySource(entryId)
        db.withTransaction {
            todos.forEach { db.todoDao().deleteById(it.id) }
            attachments.forEach { db.attachmentDao().deleteById(it.id) }
            db.entryDao().deleteById(entryId)
        }
        attachments.forEach(ImageStorage::delete)
    }

    suspend fun purgeAllTrash(): Int {
        val ids = db.entryDao().trashOnce()
        ids.forEach { purgeEntry(it.id) }
        return ids.size
    }

    /**
     * 清理超过保留期的回收站条目。启动时调用一次。
     *
     * 之所以要主动清理而不是留着：图片是磁盘占用的大头，回收站无限增长会悄悄吃掉空间。
     */
    suspend fun purgeExpiredTrash(now: Long = System.currentTimeMillis()): Int {
        val cutoff = now - TRASH_RETENTION_MS
        val ids = db.entryDao().expiredTrashIds(cutoff)
        ids.forEach { purgeEntry(it) }
        return ids.size
    }

    /** 收藏/取消收藏。 */
    suspend fun setStarred(entryId: Long, starred: Boolean) {
        db.entryDao().setStarred(entryId, starred)
    }

    suspend fun toggleStarred(entry: EntryEntity) {
        db.entryDao().setStarred(entry.id, !entry.isStarred)
    }

    // ---------- 标签管理 ----------

    /**
     * 重命名标签：把 `from` 换成 `to`。
     *
     * 标签是存在 `tagsJson` 里的 JSON 数组，没有独立表，所以只能扫一遍回写。
     * 条目量级是「个人日记」，一次全表扫描可以接受；换独立表反而要处理迁移与去重。
     */
    suspend fun renameTag(from: String, to: String): Int {
        val target = to.trim()
        if (from.isBlank() || target.isBlank() || from == target) return 0
        return retag { tags -> tags.map { if (it == from) target else it } }
    }

    /** 合并标签：把 `from` 并入 `to`（等同重命名，但语义上允许 `to` 已存在）。 */
    suspend fun mergeTag(from: String, to: String): Int = renameTag(from, to)

    /** 删除标签：只从条目上摘掉，不删除条目本身。 */
    suspend fun deleteTag(tag: String): Int = retag { tags -> tags.filterNot { it == tag } }

    /** 全库标签与出现次数，按次数倒序。 */
    suspend fun tagCounts(): List<Pair<String, Int>> {
        val counts = mutableMapOf<String, Int>()
        db.entryDao().allTagsJson().forEach { raw ->
            parseTags(raw).forEach { tag -> counts[tag] = (counts[tag] ?: 0) + 1 }
        }
        return counts.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { it.key to it.value }
    }

    private suspend fun retag(transform: (List<String>) -> List<String>): Int {
        val all = db.entryDao().allOnce()
        var changed = 0
        db.withTransaction {
            all.forEach { entry ->
                val current = parseTags(entry.tagsJson)
                val next = transform(current).distinct()
                if (next != current) {
                    db.entryDao().update(entry.copy(tagsJson = tagsJsonOf(next), updatedAt = System.currentTimeMillis()))
                    changed++
                }
            }
        }
        return changed
    }

    // ---------- 往年今日 ----------

    /**
     * 往年今日：过去若干年里同一「月-日」的记录，年份由近到远。
     *
     * 用逐年的区间查询而不是把日期格式化后比较：后者要对全表做字符串函数，
     * 无法用索引，而这里每天只需要几次范围扫描。
     * 2 月 29 日在平年不存在，`minusYears` 会抛异常，按跳过处理。
     */
    suspend fun onThisDay(yearsBack: Int = ON_THIS_DAY_YEARS): List<EntryEntity> {
        val today = LocalDate.now()
        val zone = ZoneId.systemDefault()
        val found = mutableListOf<EntryEntity>()
        for (back in 1..yearsBack) {
            val day = runCatching { today.minusYears(back.toLong()) }.getOrNull() ?: continue
            val start = day.atStartOfDay(zone).toInstant().toEpochMilli()
            val end = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            found += db.entryDao().between(start, end)
        }
        return found.sortedByDescending { it.createdAt }
    }

    // ---------- 写作统计 ----------

    /**
     * 写作统计。全部由本地数据算出，不涉及任何网络请求。
     *
     * 字数按「非空白字符数」计，中文场景下比按空格分词更接近直觉。
     */
    suspend fun writingStats(): WritingStats {
        val entries = db.entryDao().allOnce()
        val zone = ZoneId.systemDefault()
        val byDay = entries.groupBy { Instant.ofEpochMilli(it.createdAt).atZone(zone).toLocalDate() }
        val totalChars = entries.sumOf { it.content.count { ch -> !ch.isWhitespace() } }
        val hourHistogram = entries.groupingBy { Instant.ofEpochMilli(it.createdAt).atZone(zone).hour }.eachCount()
        return WritingStats(
            totalEntries = entries.size,
            totalChars = totalChars,
            averageChars = if (entries.isEmpty()) 0 else totalChars / entries.size,
            activeDays = byDay.size,
            longestStreak = longestStreak(byDay.keys),
            busiestHour = hourHistogram.maxByOrNull { it.value }?.key,
            firstAt = entries.minOfOrNull { it.createdAt },
        )
    }

    /**
     * 情绪与标签的关联：每个标签下出现最多的情绪，以及该标签的条目数。
     *
     * 只统计出现次数足够多的标签：两三条记录得出的「关联」是噪音，
     * 展示出来反而误导用户。
     */
    suspend fun moodTagCorrelation(minEntries: Int = MOOD_TAG_MIN_ENTRIES, limit: Int = 6): List<MoodTagLink> {
        val entries = db.entryDao().allOnce().filter { it.mood.isNotBlank() }
        val byTag = mutableMapOf<String, MutableList<String>>()
        entries.forEach { entry ->
            parseTags(entry.tagsJson).forEach { tag ->
                byTag.getOrPut(tag) { mutableListOf() }.add(entry.mood)
            }
        }
        return byTag.entries
            .filter { it.value.size >= minEntries }
            .map { (tag, moods) ->
                val dominant = moods.groupingBy { it }.eachCount().maxByOrNull { it.value }!!
                MoodTagLink(tag, dominant.key, dominant.value, moods.size)
            }
            .sortedByDescending { it.moodCount.toFloat() / it.total }
            .take(limit)
    }

    /** 最长连续写作天数。按自然日排序后扫一遍，跨天不连续就重新计数。 */
    private fun longestStreak(days: Set<LocalDate>): Int {
        if (days.isEmpty()) return 0
        val sorted = days.sorted()
        var best = 1
        var run = 1
        for (i in 1 until sorted.size) {
            run = if (sorted[i - 1].plusDays(1) == sorted[i]) run + 1 else 1
            if (run > best) best = run
        }
        return best
    }

    // ---------- 数据完整性 ----------

    /**
     * 自检结果。全部是「不该存在但可能因异常退出而残留」的状态。
     * [orphanFiles] 是磁盘上没有被任何附件行引用的图片文件。
     */
    data class IntegrityReport(
        val orphanFiles: Int = 0,
        val danglingTodoSources: Int = 0,
        val invalidCategories: Int = 0,
        val trashedEntries: Int = 0,
    ) {
        val clean: Boolean get() = orphanFiles == 0 && danglingTodoSources == 0 && invalidCategories == 0
    }

    suspend fun integrityReport(): IntegrityReport {
        val attachments = db.attachmentDao().allOnce()
        val known = attachments.map { it.localPath }.toSet()
        val orphanFiles = File(context.filesDir, "attachments").listFiles()
            ?.count { it.isFile && it.absolutePath !in known } ?: 0
        val entryIds = db.entryDao().allIncludingTrash().map { it.id }.toSet()
        val dangling = db.todoDao().allOnce().count { it.sourceEntryId > 0 && it.sourceEntryId !in entryIds }
        val invalid = db.entryDao().allIncludingTrash().count { it.categoryId !in Category.LIFE..Category.IDEA }
        return IntegrityReport(
            orphanFiles = orphanFiles,
            danglingTodoSources = dangling,
            invalidCategories = invalid,
            trashedEntries = db.entryDao().trashOnce().size,
        )
    }

    /**
     * 修复自检发现的问题。
     *
     * 只做「去掉指向不存在的东西」这类无争议的清理，不猜测用户意图：
     * 悬空来源链接摘掉（待办本身保留），非法分类归到默认值，孤儿图片删除。
     */
    suspend fun repairIntegrity(): String {
        val before = integrityReport()
        if (before.clean) return "没有发现需要修复的问题"

        val entryIds = db.entryDao().allIncludingTrash().map { it.id }.toSet()
        db.withTransaction {
            db.todoDao().allOnce()
                .filter { it.sourceEntryId > 0 && it.sourceEntryId !in entryIds }
                .forEach { db.todoDao().update(it.copy(sourceEntryId = 0, updatedAt = System.currentTimeMillis())) }
            db.entryDao().allIncludingTrash()
                .filter { it.categoryId !in Category.LIFE..Category.IDEA }
                .forEach { db.entryDao().update(it.copy(categoryId = Category.LIFE, updatedAt = System.currentTimeMillis())) }
        }

        val attachments = db.attachmentDao().allOnce()
        val known = attachments.map { it.localPath }.toSet()
        File(context.filesDir, "attachments").listFiles()
            ?.filter { it.isFile && it.absolutePath !in known }
            ?.forEach { it.delete() }

        return buildString {
            if (before.orphanFiles > 0) append("清理孤儿图片 ${before.orphanFiles} 个；")
            if (before.danglingTodoSources > 0) append("修复悬空来源 ${before.danglingTodoSources} 条；")
            if (before.invalidCategories > 0) append("归正分类 ${before.invalidCategories} 条；")
            if (isEmpty()) append("已检查，没有需要修复的问题")
        }.trimEnd('；')
    }

    // ---------- 双向链接 ----------

    /** `[[标题]]` 里的标题；标题取正文首行的前若干字。 */
    fun entryTitle(entry: EntryEntity): String = linkTitleOf(entry.content)

    /** 解析一条正文里引用的其它条目（按标题匹配）。 */
    suspend fun outgoingLinks(entry: EntryEntity): List<EntryEntity> {
        val titles = LINK_RE.findAll(entry.content)
            .map { it.groupValues[1].trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(MAX_LINKS)
            .toList()
        return titles.mapNotNull { db.entryDao().byTitle(it) }
    }

    /** 反链：其它条目里引用了当前条目的标题。 */
    suspend fun backlinks(entry: EntryEntity): List<EntryEntity> =
        db.entryDao().backlinks(linkTitleOf(entry.content), entry.id)

    /** 按标题打开被链接的条目；找不到返回 null，由调用方提示。 */
    suspend fun openLink(title: String): EntryEntity? = db.entryDao().byTitle(title.trim())


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

    /**
     * 把某张图片设为封面（排到最前）。列表缩略图与详情首图都按 sortOrder 取，
     * 因此只需回写这一组顺序即可，不需要额外的「封面」字段。
     */
    suspend fun moveAttachmentToFront(attachmentId: Long, entryId: Long) {
        val all = db.attachmentDao().forEntry(entryId)
        val target = all.firstOrNull { it.id == attachmentId } ?: return
        if (all.firstOrNull()?.id == target.id) return
        val reordered = listOf(target) + all.filterNot { it.id == target.id }
        db.withTransaction {
            reordered.forEachIndexed { index, attachment ->
                if (attachment.sortOrder != index) db.attachmentDao().update(attachment.copy(sortOrder = index))
            }
        }
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

        /** 回收站保留期：超过就彻底清理（含图片文件）。 */
        const val TRASH_RETENTION_MS = 30L * 24 * 60 * 60 * 1000

        /** 一条记忆最多解析多少条外链，避免异常正文拖慢详情页。 */
        private const val MAX_LINKS = 20

        /** 「往年今日」往回看几年。再往前记忆的密度已经很低，收益不大。 */
        private const val ON_THIS_DAY_YEARS = 5

        /** 标题最大长度：`[[链接]]` 太长时按前缀匹配没有意义。 */
        private const val MAX_TITLE_CHARS = 40

        /** `[[标题]]` 形式的双向链接。 */
        private val LINK_RE = Regex("\\[\\[([^\\[\\]]{1,$MAX_TITLE_CHARS})]]")

        /** 正文首行即标题；没有换行就整段截断。 */
        fun linkTitleOf(content: String): String =
            content.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(MAX_TITLE_CHARS).orEmpty()

        /** 把标签列表序列化成 `tagsJson`。空列表写 `[]`，与既有数据保持一致。 */
        fun tagsJsonOf(tags: List<String>): String = JSONArray(tags).toString()

        /** 解析 `tagsJson`；脏数据一律当空列表，不让一条坏记录影响整页。 */
        fun parseTags(raw: String): List<String> = runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { array.optString(it).takeIf { tag -> tag.isNotBlank() } }
        }.getOrDefault(emptyList())

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
