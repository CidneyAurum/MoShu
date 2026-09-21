package app.moshu.journal.ui.todo

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.moshu.journal.MoShuApp
import app.moshu.journal.data.db.TodoEntity
import app.moshu.journal.reminder.TodoReminderWorker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

data class TodosUiState(
    val active: List<TodoEntity> = emptyList(),
    val completed: List<TodoEntity> = emptyList(),
    val showCompleted: Boolean = false,
    val message: String = "",
    /** 首次查询返回前为 true，避免冷启动先闪一下空状态。 */
    val loading: Boolean = true,
)

class TodosViewModel : ViewModel() {
    private val app = MoShuApp.instance
    private val showCompleted = MutableStateFlow(false)
    private val message = MutableStateFlow("")
    private val loaded = MutableStateFlow(false)

    val uiState: StateFlow<TodosUiState> = combine(
        app.database.todoDao().observeActive().onEach { loaded.value = true },
        app.database.todoDao().observeDone(),
        showCompleted,
        message,
        loaded,
    ) { active, done, show, note, isLoaded -> TodosUiState(active, done, show, note, !isLoaded) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TodosUiState())

    fun setShowCompleted(show: Boolean) { showCompleted.value = show }

    fun clearMessage() { message.value = "" }

    fun add(text: String) {
        val value = text.trim()
        if (value.isEmpty()) return
        viewModelScope.launch {
            app.database.todoDao().insert(
                TodoEntity(text = value, sourceEntryId = 0, createdAt = System.currentTimeMillis(), isUserCreated = true)
            )
        }
    }

    fun save(todo: TodoEntity, text: String, dueEpochDay: Int?, remind: Boolean) {
        val value = text.trim()
        if (value.isEmpty()) return
        viewModelScope.launch {
            val desired = if (remind && dueEpochDay != null) {
                LocalDate.ofEpochDay(dueEpochDay.toLong())
                    .atTime(TodoReminderWorker.REMINDER_HOUR, 0)
                    .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            } else null
            // 提醒时刻已经过去就不要再存下来：否则开关显示为开，通知却永远不会到。
            val expired = desired != null && desired <= System.currentTimeMillis()
            val reminderAt = if (expired) null else desired
            val updated = todo.copy(
                text = value,
                dueEpochDay = dueEpochDay,
                reminderAt = reminderAt,
                userEdited = true,
                updatedAt = System.currentTimeMillis(),
            )
            app.database.todoDao().update(updated)
            if (updated.done || reminderAt == null) TodoReminderWorker.cancel(app, updated.uid)
            else TodoReminderWorker.schedule(app, updated)
            message.value = if (expired) "该日期的 09:00 已经过去，这次没有设置提醒。" else ""
        }
    }

    fun toggle(todo: TodoEntity) {
        viewModelScope.launch {
            val done = !todo.done
            val updated = todo.copy(done = done, completedAt = if (done) System.currentTimeMillis() else null, updatedAt = System.currentTimeMillis())
            app.database.todoDao().update(updated)
            if (done) TodoReminderWorker.cancel(app, todo.uid) else TodoReminderWorker.schedule(app, updated)
        }
    }

    fun delete(todo: TodoEntity) {
        viewModelScope.launch {
            TodoReminderWorker.cancel(app, todo.uid)
            app.database.todoDao().deleteById(todo.id)
        }
    }

    /** 一键清空已完成列表，同时取消对应提醒。 */
    fun clearCompleted() {
        viewModelScope.launch {
            uiState.value.completed.forEach { todo ->
                TodoReminderWorker.cancel(app, todo.uid)
                app.database.todoDao().deleteById(todo.id)
            }
        }
    }
}
