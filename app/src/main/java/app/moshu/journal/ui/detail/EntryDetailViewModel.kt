package app.moshu.journal.ui.detail

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.moshu.journal.MoShuApp
import app.moshu.journal.data.ReEnrichResult
import app.moshu.journal.data.db.AttachmentEntity
import app.moshu.journal.data.db.EntryEntity
import app.moshu.journal.data.db.TodoEntity
import app.moshu.journal.reminder.TodoReminderWorker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class EntryDetailUiState(
    val entry: EntryEntity? = null,
    val attachments: List<AttachmentEntity> = emptyList(),
    val todos: List<TodoEntity> = emptyList(),
    val busy: Boolean = false,
    val message: String = "",
    /** 提示是否为失败：决定提示条配色。 */
    val messageError: Boolean = false,
    /** 首次查询返回前为 true。否则「entry == null」会被误判成「已被删除」。 */
    val loading: Boolean = true,
)

class EntryDetailViewModel(savedStateHandle: SavedStateHandle) : ViewModel() {
    private val app = MoShuApp.instance
    private val entryId: Long = savedStateHandle.get<Long>("entryId") ?: 0L
    private val busy = MutableStateFlow(false)
    private val message = MutableStateFlow("")
    private val messageError = MutableStateFlow(false)
    private val loaded = MutableStateFlow(false)

    private data class Flags(val busy: Boolean, val message: String, val messageError: Boolean, val loaded: Boolean)

    private val flags = combine(busy, message, messageError, loaded) { isBusy, note, isError, isLoaded ->
        Flags(isBusy, note, isError, isLoaded)
    }

    /** 统一提示入口，避免各处只写文案而忘了同步严重程度。 */
    private fun note(text: String, error: Boolean = false) {
        message.value = text
        messageError.value = error
    }

    val uiState: StateFlow<EntryDetailUiState> = combine(
        app.database.entryDao().observeById(entryId).onEach { loaded.value = true },
        app.database.attachmentDao().observeForEntry(entryId),
        app.database.todoDao().observeForEntry(entryId),
        flags,
    ) { entry, attachments, todos, flag ->
        EntryDetailUiState(entry, attachments, todos, flag.busy, flag.message, flag.messageError, !flag.loaded)
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EntryDetailUiState())

    fun save(content: String, category: Int, tags: List<String>, mood: String, summary: String, onSaved: () -> Unit) {
        viewModelScope.launch {
            // 直接查库而不是读 uiState.value：进程重建后 .value 可能仍是初始的 null，
            // 那会让「保存」什么都不做且没有任何提示。
            val entry = app.database.entryDao().byId(entryId)
            if (entry == null) {
                note("这条记忆已经不存在", error = true)
                return@launch
            }
            busy.value = true
            try {
                app.journal.updateEntry(entry, content, category, tags, mood, summary)
                note("修改已保存")
                onSaved()
            } catch (error: Exception) {
                note("保存失败：${error.message ?: "请稍后重试"}", error = true)
            } finally {
                busy.value = false
            }
        }
    }

    fun togglePinned() {
        viewModelScope.launch {
            val entry = app.database.entryDao().byId(entryId) ?: return@launch
            app.journal.togglePinned(entry)
        }
    }

    /** 详情页直接勾选待办完成态，并联动提醒调度。 */
    fun toggleTodo(todo: TodoEntity) {
        viewModelScope.launch {
            val done = !todo.done
            val updated = todo.copy(done = done, completedAt = if (done) System.currentTimeMillis() else null, updatedAt = System.currentTimeMillis())
            app.database.todoDao().update(updated)
            if (done) TodoReminderWorker.cancel(app, todo.uid) else TodoReminderWorker.schedule(app, updated)
        }
    }

    /** 从详情页快速创建一条关联本记录的行动。 */
    fun addTodo(text: String) {
        val value = text.trim()
        if (value.isEmpty()) return
        viewModelScope.launch {
            app.database.todoDao().insert(
                TodoEntity(text = value, sourceEntryId = entryId, createdAt = System.currentTimeMillis(), isUserCreated = true)
            )
            note("已加入行动")
        }
    }

    /** 详情页删除关联行动：同样走可撤销路径，删除后不必跑到「行动」页。 */
    fun deleteTodo(todo: TodoEntity) {
        viewModelScope.launch {
            TodoReminderWorker.cancel(app, todo.uid)
            app.database.todoDao().deleteById(todo.id)
            app.notices.post("已删除「${todo.text.take(18)}」", "撤销") {
                viewModelScope.launch {
                    app.database.todoDao().insert(todo.copy(id = 0))
                    if (!todo.done && todo.reminderAt != null) {
                        app.database.todoDao().byUid(todo.uid)?.let { TodoReminderWorker.schedule(app, it) }
                    }
                }
            }
        }
    }

    /** 把某张图片设为封面（排序最前）。 */
    fun setCover(attachmentId: Long) {
        viewModelScope.launch { app.journal.moveAttachmentToFront(attachmentId, entryId) }
    }

    fun retryAi() {
        viewModelScope.launch {
            // 结果必须落成可见提示：以前无论成功、未配置还是正文为空都静默返回，
            // 用户点「重新整理」看不出任何变化。
            when (val result = app.journal.reEnrich(entryId)) {
                ReEnrichResult.Enriched -> note("已提交整理")
                ReEnrichResult.NotConfigured -> note("尚未配置 AI 服务，请到设置页填写", error = true)
                ReEnrichResult.EmptyContent -> note("正文为空，无法整理", error = true)
                ReEnrichResult.AllManual -> note("概括、标签等已由你手动设置，仅重新提取行动项")
            }
        }
    }

    /** 恢复 AI 管理：清除人工标记位后立即重新整理，让 AI 重新接管这些字段。 */
    fun clearManualMetadata(mask: Int) {
        viewModelScope.launch {
            val entry = app.database.entryDao().byId(entryId) ?: return@launch
            app.journal.clearManualMetadata(entry, mask)
            note(
                when (app.journal.reEnrich(entryId)) {
                    ReEnrichResult.Enriched, ReEnrichResult.AllManual -> "已恢复 AI 管理，正在重新整理"
                    ReEnrichResult.NotConfigured -> "已恢复 AI 管理；配置 AI 服务后会重新整理"
                    ReEnrichResult.EmptyContent -> "已恢复 AI 管理"
                }
            )
        }
    }

    /** 采纳 AI 建议的行动。 */
    fun acceptAiTodo(todo: TodoEntity) {
        viewModelScope.launch {
            app.journal.acceptAiTodo(todo)
            note("已采纳为行动")
        }
    }

    /** 忽略 AI 建议的行动。 */
    fun dismissAiTodo(todo: TodoEntity) {
        viewModelScope.launch {
            app.journal.dismissAiTodo(todo)
            note("已忽略这条建议")
        }
    }

    fun addImages(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            busy.value = true
            try {
                app.journal.addImages(entryId, uris)
            } catch (error: Exception) {
                note(error.message ?: "图片添加失败", error = true)
            } finally {
                busy.value = false
            }
        }
    }

    fun deleteImage(attachmentId: Long) {
        viewModelScope.launch {
            val removed = app.journal.deleteAttachment(attachmentId, entryId) ?: return@launch
            app.notices.post("已删除这张图片", "撤销") {
                viewModelScope.launch { app.journal.restoreAttachment(removed) }
            }
        }
    }

    fun delete(onDeleted: () -> Unit) {
        viewModelScope.launch {
            try {
                val deleted = app.journal.deleteEntry(entryId)
                onDeleted()
                if (deleted != null) {
                    // 删除后先离开详情页，撤销入口由全局 snackbar 承接。
                    app.notices.post("已删除这条记忆", "撤销") {
                        viewModelScope.launch { app.journal.restoreEntry(deleted) }
                    }
                }
            } catch (error: Exception) {
                note("删除失败：${error.message ?: "请稍后重试"}", error = true)
            }
        }
    }
}
