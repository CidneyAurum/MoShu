package app.moshu.journal.ui.record

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.moshu.journal.MoShuApp
import app.moshu.journal.data.db.AttachmentEntity
import app.moshu.journal.data.db.Category
import app.moshu.journal.data.db.EntryAiState
import app.moshu.journal.data.db.EntryEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 记忆列表排序。置顶始终优先，这里只决定其余部分的顺序。 */
enum class EntrySort { NEWEST, OLDEST, UPDATED }

data class MemoryFilters(
    val category: Int? = null,
    val mood: String? = null,
    val pinnedOnly: Boolean = false,
    val query: String = "",
    val searchMode: Boolean = false,
    /** 只看 AI 整理失败的条目；失败通知 deep link 会默认打开它。 */
    val failedOnly: Boolean = false,
    val sort: EntrySort = EntrySort.NEWEST,
) {
    /** 除排序外是否有筛选生效，决定「清除」按钮是否出现。 */
    val active: Boolean
        get() = category != null || mood != null || pinnedOnly || failedOnly || query.isNotBlank()
}

data class RecordUiState(
    val entries: List<EntryEntity> = emptyList(),
    val attachments: Map<Long, List<AttachmentEntity>> = emptyMap(),
    val filters: MemoryFilters = MemoryFilters(),
    val pendingCount: Int = 0,
    /** 首次查询返回之前为 true，避免先闪一下「没有找到相关记忆」。 */
    val loading: Boolean = true,
)

@OptIn(ExperimentalCoroutinesApi::class)
class RecordViewModel : ViewModel() {
    private val app = MoShuApp.instance
    private val filters = MutableStateFlow(MemoryFilters())
    private val loading = MutableStateFlow(true)

    private val entries = filters.flatMapLatest { filter ->
        app.journal.observe(
            category = if (filter.searchMode) null else filter.category,
            query = if (filter.searchMode) filter.query else "",
        )
    }.onEach { loading.value = false }

    val uiState: StateFlow<RecordUiState> = entries.flatMapLatest { source ->
        combine(
            app.journal.observeAttachments(source.map { it.id }),
            filters,
            app.journal.pendingCount,
        ) { attachments, filter, pending ->
            // 搜索时也要遵守分类筛选：否则「工作」筛选块显示为选中，
            // 结果里却混着所有分类，用户看到的和选中的对不上。
            val visible = source.filter { entry ->
                (filter.category == null || entry.categoryId == filter.category) &&
                    (filter.mood == null || entry.mood == filter.mood) &&
                    (!filter.pinnedOnly || entry.isPinned) &&
                    (!filter.failedOnly || entry.aiState == EntryAiState.FAILED.value)
            }
            RecordUiState(sortEntries(visible, filter.sort), attachments.groupBy { it.entryId }, filter, pending, loading.value)
        }
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

    fun setFailedOnly(enabled: Boolean) {
        filters.value = filters.value.copy(failedOnly = enabled)
    }

    fun setSort(sort: EntrySort) {
        filters.value = filters.value.copy(sort = sort)
    }

    /** 一键清掉所有筛选（排序保留，它不算筛选）。 */
    fun clearFilters() {
        filters.value = MemoryFilters(sort = filters.value.sort)
    }

    fun setQuery(query: String) {
        filters.value = filters.value.copy(query = query)
    }

    fun toggleSearch() {
        val enabled = !filters.value.searchMode
        filters.value = filters.value.copy(searchMode = enabled, query = if (enabled) filters.value.query else "")
    }

    fun togglePinned(entry: EntryEntity) {
        viewModelScope.launch { app.journal.togglePinned(entry) }
    }

    fun duplicate(entry: EntryEntity) {
        viewModelScope.launch { app.journal.duplicateEntry(entry.id) }
    }

    fun delete(entry: EntryEntity) {
        viewModelScope.launch {
            val deleted = app.journal.deleteEntry(entry.id) ?: return@launch
            app.notices.post("已删除这条记忆", "撤销") {
                viewModelScope.launch { app.journal.restoreEntry(deleted) }
            }
        }
    }
}

/** 置顶恒在前，其余按所选维度排列。 */
internal fun sortEntries(entries: List<EntryEntity>, sort: EntrySort): List<EntryEntity> =
    entries.sortedWith(
        when (sort) {
            EntrySort.NEWEST -> compareByDescending<EntryEntity> { it.isPinned }.thenByDescending { it.createdAt }
            EntrySort.OLDEST -> compareByDescending<EntryEntity> { it.isPinned }.thenBy { it.createdAt }
            EntrySort.UPDATED -> compareByDescending<EntryEntity> { it.isPinned }.thenByDescending { it.updatedAt }
        }
    )
