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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

data class TodosUiState(
    val active: List<TodoEntity> = emptyList(),
    val completed: List<TodoEntity> = emptyList(),
    val showCompleted: Boolean = false,
)

class TodosViewModel : ViewModel() {
    private val app = MoShuApp.instance
    private val showCompleted = MutableStateFlow(false)

    val uiState: StateFlow<TodosUiState> = combine(
        app.database.todoDao().observeActive(),
        app.database.todoDao().observeDone(),
        showCompleted,
    ) { active, done, show -> TodosUiState(active, done, show) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TodosUiState())

    fun setShowCompleted(show: Boolean) { showCompleted.value = show }

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
            val reminderAt = if (remind && dueEpochDay != null) {
                LocalDate.ofEpochDay(dueEpochDay.toLong()).atTime(9, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            } else null
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
}
