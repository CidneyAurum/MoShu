package app.moshu.journal.ui.today

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.moshu.journal.MoShuApp
import app.moshu.journal.data.db.AttachmentEntity
import app.moshu.journal.data.db.EntryEntity
import app.moshu.journal.data.db.TodoEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

data class TodayUiState(
    val entries: List<EntryEntity> = emptyList(),
    val attachments: Map<Long, List<AttachmentEntity>> = emptyMap(),
    val activeTodos: List<TodoEntity> = emptyList(),
    val pendingCount: Int = 0,
    val saving: Boolean = false,
)

class TodayViewModel : ViewModel() {
    private val app = MoShuApp.instance
    private val saving = MutableStateFlow(false)
    private val todayStart = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    val uiState: StateFlow<TodayUiState> = combine(
        app.database.entryDao().observeToday(todayStart),
        app.database.attachmentDao().observeAll(),
        app.database.todoDao().observeActive(),
        app.journal.pendingCount,
        saving,
    ) { entries, attachments, todos, pending, busy ->
        TodayUiState(
            entries = entries,
            attachments = attachments.groupBy { it.entryId },
            activeTodos = todos,
            pendingCount = pending,
            saving = busy,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TodayUiState())

    fun add(content: String, images: List<Uri>, onSaved: () -> Unit = {}) {
        if (saving.value || content.isBlank() && images.isEmpty()) return
        viewModelScope.launch {
            saving.value = true
            try {
                app.journal.addEntry(content, images)
                onSaved()
            } finally {
                saving.value = false
            }
        }
    }
}
