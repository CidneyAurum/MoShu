package app.moshu.journal.data.backup

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import app.moshu.journal.BuildConfig
import app.moshu.journal.data.db.AiReviewEntity
import app.moshu.journal.data.db.AppDatabase
import app.moshu.journal.data.db.AttachmentEntity
import app.moshu.journal.data.db.EntryEntity
import app.moshu.journal.data.db.TodoEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

object BackupManager {
    data class RestoreResult(val entries: Int, val todos: Int, val images: Int)

    suspend fun exportBackup(context: Context, db: AppDatabase, uri: Uri) = withContext(Dispatchers.IO) {
        val entries = db.entryDao().allOnce()
        val todos = db.todoDao().allOnce()
        val attachments = db.attachmentDao().allOnce()
        val reviews = db.aiReviewDao().allOnce()
        val entryUidById = entries.associate { it.id to it.uid }

        val output = context.contentResolver.openOutputStream(uri, "wt") ?: error("无法创建备份文件")
        ZipOutputStream(output.buffered()).use { zip ->
            zip.putText("manifest.json", JSONObject().apply {
                put("format", 1)
                put("appVersion", BuildConfig.VERSION_NAME)
                put("createdAt", System.currentTimeMillis())
                put("warning", "This backup is not encrypted and never contains API keys.")
            }.toString(2))
            zip.putText("entries.json", JSONArray(entries.map(::entryJson)).toString())
            zip.putText("todos.json", JSONArray(todos.map { todoJson(it, entryUidById[it.sourceEntryId]) }).toString())
            zip.putText("reviews.json", JSONArray(reviews.map(::reviewJson)).toString())
            zip.putText("attachments.json", JSONArray(attachments.map { attachmentJson(it, entryUidById[it.entryId].orEmpty()) }).toString())
            attachments.forEach { attachment ->
                val file = File(attachment.localPath)
                if (file.isFile) {
                    zip.putNextEntry(ZipEntry("attachments/${attachment.uid}.jpg"))
                    file.inputStream().buffered().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
    }

    suspend fun exportMarkdown(context: Context, db: AppDatabase, uri: Uri) = withContext(Dispatchers.IO) {
        val format = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)
        val body = buildString {
            appendLine("# 墨枢记忆导出")
            appendLine()
            db.entryDao().allOnce().sortedByDescending { it.createdAt }.forEach { entry ->
                appendLine("## ${format.format(Date(entry.createdAt))}")
                if (entry.summary.isNotBlank()) appendLine("**${entry.summary}**")
                appendLine()
                appendLine(entry.content)
                val tags = runCatching { JSONArray(entry.tagsJson) }.getOrNull()
                if (tags != null && tags.length() > 0) {
                    appendLine()
                    appendLine((0 until tags.length()).joinToString(" ") { "#${tags.optString(it)}" })
                }
                appendLine()
            }
        }
        context.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter(Charsets.UTF_8)?.use { it.write(body) }
            ?: error("无法创建导出文件")
    }

    suspend fun restore(context: Context, db: AppDatabase, uri: Uri, replace: Boolean): RestoreResult = withContext(Dispatchers.IO) {
        val temp = File.createTempFile("moshu_restore_", ".zip", context.cacheDir)
        try {
            context.contentResolver.openInputStream(uri)?.use { input -> temp.outputStream().use { input.copyTo(it) } }
                ?: error("无法读取备份文件")
            ZipFile(temp).use { zip ->
                val manifest = JSONObject(zip.readText("manifest.json"))
                require(manifest.optInt("format") == 1) { "不支持的备份版本" }
                val entriesJson = JSONArray(zip.readText("entries.json"))
                val todosJson = JSONArray(zip.readText("todos.json"))
                val attachmentsJson = JSONArray(zip.readText("attachments.json"))
                val reviewsJson = runCatching { JSONArray(zip.readText("reviews.json")) }.getOrDefault(JSONArray())
                val copiedFiles = mutableListOf<File>()
                var entryCount = 0
                var todoCount = 0
                var imageCount = 0

                db.withTransaction {
                    if (replace) {
                        db.attachmentDao().allOnce().forEach { File(it.localPath).delete() }
                        db.attachmentDao().deleteAll()
                        db.todoDao().deleteAll()
                        db.aiReviewDao().deleteAll()
                        db.entryDao().deleteAll()
                    }

                    val uidToId = db.entryDao().allOnce().associate { it.uid to it.id }.toMutableMap()
                    for (index in 0 until entriesJson.length()) {
                        val item = entriesJson.getJSONObject(index)
                        val uid = item.optString("uid").ifBlank { UUID.randomUUID().toString() }
                        if (uid !in uidToId) {
                            val id = db.entryDao().insert(parseEntry(item, uid))
                            uidToId[uid] = id
                            entryCount++
                        }
                    }

                    for (index in 0 until todosJson.length()) {
                        val item = todosJson.getJSONObject(index)
                        val uid = item.optString("uid").ifBlank { UUID.randomUUID().toString() }
                        if (db.todoDao().byUid(uid) == null) {
                            val sourceId = uidToId[item.optString("sourceEntryUid")] ?: 0L
                            db.todoDao().insert(parseTodo(item, uid, sourceId))
                            todoCount++
                        }
                    }

                    val dir = File(context.filesDir, "attachments").apply { mkdirs() }
                    for (index in 0 until attachmentsJson.length()) {
                        val item = attachmentsJson.getJSONObject(index)
                        val uid = item.optString("uid").ifBlank { UUID.randomUUID().toString() }
                        if (db.attachmentDao().byUid(uid) != null) continue
                        val entryId = uidToId[item.optString("entryUid")] ?: continue
                        val zipEntry = zip.getEntry("attachments/$uid.jpg") ?: continue
                        require(!zipEntry.name.contains("..")) { "备份包含非法路径" }
                        val target = File(dir, "$uid.jpg")
                        zip.getInputStream(zipEntry).use { input -> target.outputStream().use { input.copyTo(it) } }
                        copiedFiles += target
                        db.attachmentDao().insert(parseAttachment(item, uid, entryId, target.absolutePath))
                        imageCount++
                    }

                    for (index in 0 until reviewsJson.length()) db.aiReviewDao().upsert(parseReview(reviewsJson.getJSONObject(index)))
                }
                RestoreResult(entryCount, todoCount, imageCount)
            }
        } finally {
            temp.delete()
        }
    }

    private fun ZipOutputStream.putText(name: String, value: String) {
        putNextEntry(ZipEntry(name))
        write(value.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private fun ZipFile.readText(name: String): String {
        val entry = getEntry(name) ?: error("备份缺少 $name")
        require(!entry.name.contains("..")) { "备份包含非法路径" }
        return getInputStream(entry).bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    private fun entryJson(e: EntryEntity) = JSONObject().apply {
        put("uid", e.uid); put("content", e.content); put("categoryId", e.categoryId); put("tagsJson", e.tagsJson)
        put("summary", e.summary); put("mood", e.mood); put("enriched", e.enriched); put("createdAt", e.createdAt)
        put("updatedAt", e.updatedAt); put("isPinned", e.isPinned); put("aiState", e.aiState); put("manualMetadataMask", e.manualMetadataMask)
    }

    private fun todoJson(t: TodoEntity, sourceUid: String?) = JSONObject().apply {
        put("uid", t.uid); put("text", t.text); put("sourceEntryUid", sourceUid.orEmpty()); put("createdAt", t.createdAt)
        put("dueEpochDay", t.dueEpochDay ?: JSONObject.NULL); put("done", t.done); put("updatedAt", t.updatedAt)
        put("completedAt", t.completedAt ?: JSONObject.NULL); put("reminderAt", t.reminderAt ?: JSONObject.NULL)
        put("isUserCreated", t.isUserCreated); put("userEdited", t.userEdited)
    }

    private fun attachmentJson(a: AttachmentEntity, entryUid: String) = JSONObject().apply {
        put("uid", a.uid); put("entryUid", entryUid); put("mimeType", a.mimeType); put("width", a.width); put("height", a.height)
        put("sortOrder", a.sortOrder); put("createdAt", a.createdAt)
    }

    private fun reviewJson(r: AiReviewEntity) = JSONObject().apply {
        put("periodKey", r.periodKey); put("periodType", r.periodType); put("content", r.content); put("sourceUidsJson", r.sourceUidsJson); put("generatedAt", r.generatedAt)
    }

    private fun parseEntry(o: JSONObject, uid: String) = EntryEntity(
        uid = uid, content = o.optString("content"), categoryId = o.optInt("categoryId"), tagsJson = o.optString("tagsJson", "[]"),
        summary = o.optString("summary"), mood = o.optString("mood"), enriched = o.optBoolean("enriched"),
        createdAt = o.optLong("createdAt", System.currentTimeMillis()), updatedAt = o.optLong("updatedAt", o.optLong("createdAt")),
        isPinned = o.optBoolean("isPinned"), aiState = o.optString("aiState", "idle"), manualMetadataMask = o.optInt("manualMetadataMask"),
    )

    private fun parseTodo(o: JSONObject, uid: String, sourceId: Long) = TodoEntity(
        uid = uid, text = o.optString("text"), sourceEntryId = sourceId, createdAt = o.optLong("createdAt", System.currentTimeMillis()),
        dueEpochDay = o.nullableInt("dueEpochDay"), done = o.optBoolean("done"), updatedAt = o.optLong("updatedAt", o.optLong("createdAt")),
        completedAt = o.nullableLong("completedAt"), reminderAt = o.nullableLong("reminderAt"), isUserCreated = o.optBoolean("isUserCreated"), userEdited = o.optBoolean("userEdited"),
    )

    private fun parseAttachment(o: JSONObject, uid: String, entryId: Long, path: String) = AttachmentEntity(
        uid = uid, entryId = entryId, localPath = path, mimeType = o.optString("mimeType", "image/jpeg"), width = o.optInt("width"), height = o.optInt("height"), sortOrder = o.optInt("sortOrder"), createdAt = o.optLong("createdAt"),
    )

    private fun parseReview(o: JSONObject) = AiReviewEntity(o.optString("periodKey"), o.optString("periodType"), o.optString("content"), o.optString("sourceUidsJson", "[]"), o.optLong("generatedAt"))
    private fun JSONObject.nullableLong(key: String): Long? = if (isNull(key) || !has(key)) null else optLong(key)
    private fun JSONObject.nullableInt(key: String): Int? = if (isNull(key) || !has(key)) null else optInt(key)
}
