package app.moshu.journal.ui.record

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.moshu.journal.MoShuApp
import app.moshu.journal.data.db.AttachmentEntity
import app.moshu.journal.data.db.Category
import app.moshu.journal.data.db.EntryEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn

data class MemoryFilters(
    val category: Int? = null,
    val mood: String? = null,
    val pinnedOnly: Boolean = false,
    val query: String = "",
    val searchMode: Boolean = false,
)

data class RecordUiState(
    val entries: List<EntryEntity> = emptyList(),
    val attachments: Map<Long, List<AttachmentEntity>> = emptyMap(),
    val filters: MemoryFilters = MemoryFilters(),
    val pendingCount: Int = 0,
)

@OptIn(ExperimentalCoroutinesApi::class)
class RecordViewModel : ViewModel() {
    private val app = MoShuApp.instance
    private val filters = MutableStateFlow(MemoryFilters())

    private val entries = filters.flatMapLatest { filter ->
        app.journal.observe(
            category = if (filter.searchMode) null else filter.category,
            query = if (filter.searchMode) filter.query else "",
        )
    }

    val uiState: StateFlow<RecordUiState> = combine(
        entries,
        app.database.attachmentDao().observeAll(),
        filters,
        app.journal.pendingCount,
    ) { source, attachments, filter, pending ->
        val visible = source.filter { entry ->
            (filter.mood == null || entry.mood == filter.mood) && (!filter.pinnedOnly || entry.isPinned)
        }
        RecordUiState(visible, attachments.groupBy { it.entryId }, filter, pending)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RecordUiState())

    fun setCategory(categoryId: Int?) {
        if (categoryId != null && categoryId !in Category.LIFE..Category.IDEA) return
        filters.value = filters.value.copy(category = categoryId)
    }

    fun setMood(mood: String?) {
        filters.value = filters.value.copy(mood = mood)
    }

    fun togglePinnedOnly() {
        filters.value = filters.value.copy(pinnedOnly = !filters.value.pinnedOnly)
    }

    fun setQuery(query: String) {
        filters.value = filters.value.copy(query = query)
    }

    fun toggleSearch() {
        val enabled = !filters.value.searchMode
        filters.value = filters.value.copy(searchMode = enabled, query = if (enabled) filters.value.query else "")
    }
}
