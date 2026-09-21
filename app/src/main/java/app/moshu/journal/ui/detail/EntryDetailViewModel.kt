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
    /** 首次查询返回前为 true。否则「entry == null」会被误判成「已被删除」。 */
    val loading: Boolean = true,
)

class EntryDetailViewModel(savedStateHandle: SavedStateHandle) : ViewModel() {
    private val app = MoShuApp.instance
    private val entryId: Long = savedStateHandle.get<Long>("entryId") ?: 0L
    private val busy = MutableStateFlow(false)
    private val message = MutableStateFlow("")
    private val loaded = MutableStateFlow(false)

    private data class Flags(val busy: Boolean, val message: String, val loaded: Boolean)

    private val flags = combine(busy, message, loaded) { isBusy, note, isLoaded -> Flags(isBusy, note, isLoaded) }

    val uiState: StateFlow<EntryDetailUiState> = combine(
        app.database.entryDao().observeById(entryId).onEach { loaded.value = true },
        app.database.attachmentDao().observeForEntry(entryId),
        app.database.todoDao().observeForEntry(entryId),
        flags,
    ) { entry, attachments, todos, flag ->
        EntryDetailUiState(entry, attachments, todos, flag.busy, flag.message, !flag.loaded)
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EntryDetailUiState())

    fun save(content: String, category: Int, tags: List<String>, mood: String, summary: String, onSaved: () -> Unit) {
        viewModelScope.launch {
            // 直接查库而不是读 uiState.value：进程重建后 .value 可能仍是初始的 null，
            // 那会让「保存」什么都不做且没有任何提示。
            val entry = app.database.entryDao().byId(entryId)
            if (entry == null) {
                message.value = "这条记忆已经不存在"
                return@launch
            }
            busy.value = true
            try {
                app.journal.updateEntry(entry, content, category, tags, mood, summary)
                message.value = "修改已保存"
                onSaved()
            } catch (error: Exception) {
                message.value = "保存失败：${error.message ?: "请稍后重试"}"
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
            message.value = "已加入行动"
        }
    }

    fun retryAi() {
        viewModelScope.launch {
            // 结果必须落成可见提示：以前无论成功、未配置还是正文为空都静默返回，
            // 用户点「重新整理」看不出任何变化。
            message.value = when (app.journal.reEnrich(entryId)) {
                ReEnrichResult.Enriched -> "已提交整理"
                ReEnrichResult.NotConfigured -> "尚未配置 AI 服务，请到设置页填写"
                ReEnrichResult.EmptyContent -> "正文为空，无法整理"
                ReEnrichResult.AllManual -> "概括、标签等已由你手动设置，仅重新提取行动项"
            }
        }
    }

    /** 恢复 AI 管理：清除人工标记位后立即重新整理，让 AI 重新接管这些字段。 */
    fun clearManualMetadata(mask: Int) {
        viewModelScope.launch {
            val entry = app.database.entryDao().byId(entryId) ?: return@launch
            app.journal.clearManualMetadata(entry, mask)
            message.value = when (app.journal.reEnrich(entryId)) {
                ReEnrichResult.Enriched, ReEnrichResult.AllManual -> "已恢复 AI 管理，正在重新整理"
                ReEnrichResult.NotConfigured -> "已恢复 AI 管理；配置 AI 服务后会重新整理"
                ReEnrichResult.EmptyContent -> "已恢复 AI 管理"
            }
        }
    }

    /** 采纳 AI 建议的行动。 */
    fun acceptAiTodo(todo: TodoEntity) {
        viewModelScope.launch {
            app.journal.acceptAiTodo(todo)
            message.value = "已采纳为行动"
        }
    }

    /** 忽略 AI 建议的行动。 */
    fun dismissAiTodo(todo: TodoEntity) {
        viewModelScope.launch {
            app.journal.dismissAiTodo(todo)
            message.value = "已忽略这条建议"
        }
    }

    fun addImages(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            busy.value = true
            try {
                app.journal.addImages(entryId, uris)
            } catch (error: Exception) {
                message.value = error.message ?: "图片添加失败"
            } finally {
                busy.value = false
            }
        }
    }

    fun deleteImage(attachmentId: Long) {
        viewModelScope.launch { app.journal.deleteAttachment(attachmentId, entryId) }
    }

    fun delete(onDeleted: () -> Unit) {
        viewModelScope.launch {
            try {
                app.journal.deleteEntry(entryId)
                onDeleted()
            } catch (error: Exception) {
                message.value = "删除失败：${error.message ?: "请稍后重试"}"
            }
        }
    }
}
