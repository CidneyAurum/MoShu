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
import java.time.format.DateTimeFormatter

data class TodosUiState(
    val active: List<TodoEntity> = emptyList(),
    /** AI 提取但尚未被采纳的行动。它们不属于正式列表，单独成区等待用户表态。 */
    val suggested: List<TodoEntity> = emptyList(),
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
        app.database.todoDao().observeSuggested(),
        app.database.todoDao().observeDone(),
        showCompleted,
        message,
    ) { active, suggested, done, show, note ->
        TodosUiState(active, suggested, done, show, note, !loaded.value)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TodosUiState())

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
            app.notices.post("已删除「${todo.text.take(18)}」", "撤销") {
                viewModelScope.launch { reinsert(listOf(todo)) }
            }
        }
    }

    /**
     * 批量改截止日。
     *
     * 只改日期不动提醒：用户在多选里点「设为明天」时并没有表态要不要提醒，
     * 悄悄排一条通知会让人意外。已有提醒的条目按其新日期顺延。
     */
    fun setDue(todos: List<TodoEntity>, epochDay: Int?) {
        if (todos.isEmpty()) return
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            todos.forEach { todo ->
                val reminderAt = if (todo.reminderAt != null && epochDay != null) {
                    LocalDate.ofEpochDay(epochDay.toLong())
                        .atTime(TodoReminderWorker.REMINDER_HOUR, 0)
                        .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                } else todo.reminderAt
                val updated = todo.copy(
                    dueEpochDay = epochDay,
                    reminderAt = reminderAt,
                    updatedAt = now,
                )
                app.database.todoDao().update(updated)
                if (reminderAt == null || updated.done) TodoReminderWorker.cancel(app, todo.uid)
                else TodoReminderWorker.schedule(app, updated)
            }
            message.value = "已把 ${todos.size} 条行动的截止日改成${dueLabel(epochDay)}。"
        }
    }

    /** 批量删除：整批只占一个撤销入口，撤销时一次性全部恢复。 */
    fun deleteMany(todos: List<TodoEntity>) {
        if (todos.isEmpty()) return
        viewModelScope.launch {
            todos.forEach { todo ->
                TodoReminderWorker.cancel(app, todo.uid)
                app.database.todoDao().deleteById(todo.id)
            }
            app.notices.post("已删除 ${todos.size} 条行动", "撤销") {
                viewModelScope.launch { reinsert(todos) }
            }
        }
    }

    private fun dueLabel(epochDay: Int?): String {
        if (epochDay == null) return "未设置"
        val today = LocalDate.now().toEpochDay()
        return when (epochDay.toLong() - today) {
            0L -> "今天"
            1L -> "明天"
            7L -> "下周"
            else -> LocalDate.ofEpochDay(epochDay.toLong()).format(DateTimeFormatter.ofPattern("M月d日"))
        }
    }

    /** 一键清空已完成列表，同时取消对应提醒。整批删除可以一次撤销。 */
    fun clearCompleted() {
        viewModelScope.launch {
            val removed = uiState.value.completed
            if (removed.isEmpty()) return@launch
            removed.forEach { todo ->
                TodoReminderWorker.cancel(app, todo.uid)
                app.database.todoDao().deleteById(todo.id)
            }
            app.notices.post("已清空 ${removed.size} 条已完成行动", "撤销") {
                viewModelScope.launch { reinsert(removed) }
            }
        }
    }

    /** 把已完成退回进行中；截止日仍在则按需重新排提醒。 */
    fun restore(todo: TodoEntity) {
        viewModelScope.launch {
            val updated = todo.copy(done = false, completedAt = null, updatedAt = System.currentTimeMillis())
            app.database.todoDao().update(updated)
            if (updated.reminderAt != null) TodoReminderWorker.schedule(app, updated)
        }
    }

    /** 采纳 AI 建议：转为用户自有行动后才会出现在正式列表里。 */
    fun acceptSuggested(todo: TodoEntity) {
        viewModelScope.launch { app.journal.acceptAiTodo(todo) }
    }

    fun dismissSuggested(todo: TodoEntity) {
        viewModelScope.launch { app.journal.dismissAiTodo(todo) }
    }

    private suspend fun reinsert(todos: List<TodoEntity>) {
        todos.forEach { todo ->
            app.database.todoDao().insert(todo.copy(id = 0))
            if (!todo.done && todo.reminderAt != null) {
                app.database.todoDao().byUid(todo.uid)?.let { TodoReminderWorker.schedule(app, it) }
            }
        }
    }
}
