package app.moshu.journal.data

import android.content.Context
import android.net.Uri
import app.moshu.journal.ai.EnrichmentWorker
import app.moshu.journal.data.db.AppDatabase
import app.moshu.journal.data.db.Category
import app.moshu.journal.data.db.EntryAiState
import app.moshu.journal.data.db.EntryEntity
import app.moshu.journal.data.db.ManualMetadata
import app.moshu.journal.data.media.ImageStorage
import app.moshu.journal.data.settings.SettingsRepository
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray

class JournalRepository(
    private val context: Context,
    private val db: AppDatabase,
    private val settings: SettingsRepository,
) {
    val pendingCount: Flow<Int> = db.entryDao().observePendingCount()

    fun observe(category: Int?, query: String): Flow<List<EntryEntity>> {
        val dao = db.entryDao()
        return when {
            query.isNotBlank() -> runCatching { dao.searchFts(toFtsQuery(query)) }.getOrElse { dao.search(query.trim()) }
            category != null -> dao.observeByCategory(category)
            else -> dao.observeAll()
        }
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
        val contentChanged = content.trim() != entry.content
        val aiConfigured = settings.currentAiConfig().valid
        val mask = entry.manualMetadataMask or ManualMetadata.CATEGORY or ManualMetadata.TAGS or ManualMetadata.MOOD or ManualMetadata.SUMMARY
        val updated = entry.copy(
            content = content.trim(),
            categoryId = categoryId.coerceIn(Category.LIFE, Category.IDEA),
            tagsJson = JSONArray(tags.map { it.trim() }.filter { it.isNotEmpty() }.take(6)).toString(),
            mood = mood,
            summary = summary.trim().take(60),
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

    suspend fun reEnrich(entryId: Long) {
        val entry = db.entryDao().byId(entryId) ?: return
        if (!settings.currentAiConfig().valid || entry.content.isBlank()) return
        db.entryDao().updateAiState(entryId, EntryAiState.PENDING.value)
        EnrichmentWorker.enqueue(context, entryId)
    }

    suspend fun addImages(entryId: Long, uris: List<Uri>) {
        val existing = db.attachmentDao().forEntry(entryId)
        uris.take((ImageStorage.MAX_ATTACHMENTS - existing.size).coerceAtLeast(0)).forEachIndexed { index, uri ->
            db.attachmentDao().insert(ImageStorage.importImage(context, uri, entryId, existing.size + index))
        }
        reEnrich(entryId)
    }

    suspend fun deleteAttachment(attachmentId: Long, entryId: Long) {
        val attachment = db.attachmentDao().forEntry(entryId).firstOrNull { it.id == attachmentId } ?: return
        ImageStorage.delete(attachment)
        db.attachmentDao().deleteById(attachmentId)
    }

    suspend fun deleteEntry(entryId: Long) {
        db.attachmentDao().forEntry(entryId).forEach(ImageStorage::delete)
        val sourcedTodos = db.todoDao().bySource(entryId)
        sourcedTodos.filter { it.userEdited }.forEach {
            db.todoDao().update(it.copy(sourceEntryId = 0, isUserCreated = true, updatedAt = System.currentTimeMillis()))
        }
        db.todoDao().deleteUneditedBySource(entryId)
        db.entryDao().deleteById(entryId)
    }

    private fun toFtsQuery(raw: String): String {
        val terms = raw.trim().split(Regex("\\s+")).map { it.replace(Regex("[^\\p{L}\\p{N}_-]"), "") }.filter { it.isNotBlank() }
        return terms.joinToString(" AND ") { "\"$it\"*" }.ifBlank { "\"${raw.trim().replace("\"", "")}\"" }
    }
}
