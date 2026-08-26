package app.moshu.journal.ui.detail

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.moshu.journal.MoShuApp
import app.moshu.journal.data.db.AttachmentEntity
import app.moshu.journal.data.db.EntryEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class EntryDetailUiState(
    val entry: EntryEntity? = null,
    val attachments: List<AttachmentEntity> = emptyList(),
    val busy: Boolean = false,
    val message: String = "",
)

class EntryDetailViewModel(savedStateHandle: SavedStateHandle) : ViewModel() {
    private val app = MoShuApp.instance
    private val entryId: Long = checkNotNull(savedStateHandle["entryId"])
    private val busy = MutableStateFlow(false)
    private val message = MutableStateFlow("")

    val uiState: StateFlow<EntryDetailUiState> = combine(
        app.database.entryDao().observeById(entryId),
        app.database.attachmentDao().observeForEntry(entryId),
        busy,
        message,
    ) { entry, attachments, isBusy, note -> EntryDetailUiState(entry, attachments, isBusy, note) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EntryDetailUiState())

    fun save(content: String, category: Int, tags: List<String>, mood: String, summary: String, onSaved: () -> Unit) {
        val entry = uiState.value.entry ?: return
        viewModelScope.launch {
            busy.value = true
            try {
                app.journal.updateEntry(entry, content, category, tags, mood, summary)
                message.value = "修改已保存"
                onSaved()
            } finally {
                busy.value = false
            }
        }
    }

    fun togglePinned() {
        val entry = uiState.value.entry ?: return
        viewModelScope.launch { app.journal.togglePinned(entry) }
    }

    fun retryAi() {
        viewModelScope.launch { app.journal.reEnrich(entryId) }
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
            app.journal.deleteEntry(entryId)
            onDeleted()
        }
    }
}
