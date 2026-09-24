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
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

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
    /** 只看某一天；用来在长列表里快速定位，而不是靠滚动找。 */
    val day: LocalDate? = null,
    /** 只看收藏。收藏与置顶不同：置顶改排序，收藏只是标记。 */
    val starredOnly: Boolean = false,
) {
    /** 除排序外是否有筛选生效，决定「清除」按钮是否出现。 */
    val active: Boolean
        get() = category != null || mood != null || pinnedOnly || failedOnly || query.isNotBlank() || day != null || starredOnly
}

data class RecordUiState(
    val entries: List<EntryEntity> = emptyList(),
    val attachments: Map<Long, List<AttachmentEntity>> = emptyMap(),
    val filters: MemoryFilters = MemoryFilters(),
    val pendingCount: Int = 0,
    /** 首次查询返回之前为 true，避免先闪一下「没有找到相关记忆」。 */
    val loading: Boolean = true,
    /** 多选导出模式。 */
    val selecting: Boolean = false,
    /** 已勾选的条目 id。 */
    val selectedIds: Set<Long> = emptySet(),
)

@OptIn(ExperimentalCoroutinesApi::class)
class RecordViewModel : ViewModel() {
    private val app = MoShuApp.instance
    private val filters = MutableStateFlow(MemoryFilters())
    private val loading = MutableStateFlow(true)
    private val selecting = MutableStateFlow(false)
    private val selectedIds = MutableStateFlow<Set<Long>>(emptySet())

    private val entries = filters.flatMapLatest { filter ->
        app.journal.observe(
            category = if (filter.searchMode) null else filter.category,
            query = if (filter.searchMode) filter.query else "",
        )
    }.onEach { loading.value = false }

    /** 最近搜索词。只在搜索框为空时展示，点一下即复用。 */
    val recentSearches: StateFlow<List<String>> = app.settings.recentSearches
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 提交一次搜索（回车或点最近词），记入历史。 */
    fun commitSearch() {
        viewModelScope.launch { app.settings.rememberSearch(filters.value.query) }
    }

    fun clearRecentSearches() {
        viewModelScope.launch { app.settings.clearRecentSearches() }
    }

    val uiState: StateFlow<RecordUiState> = entries.flatMapLatest { source ->
        combine(
            app.journal.observeAttachments(source.map { it.id }),
            filters,
            app.journal.pendingCount,
            selecting,
            selectedIds,
        ) { attachments, filter, pending, inSelection, ids ->
            // 搜索时也要遵守分类筛选：否则「工作」筛选块显示为选中，
            // 结果里却混着所有分类，用户看到的和选中的对不上。
            val visible = source.filter { entry ->
                (filter.category == null || entry.categoryId == filter.category) &&
                    (filter.mood == null || entry.mood == filter.mood) &&
                    (!filter.pinnedOnly || entry.isPinned) &&
                    (!filter.failedOnly || entry.aiState == EntryAiState.FAILED.value) &&
                    (!filter.starredOnly || entry.isStarred) &&
                    (filter.day == null || entryDay(entry) == filter.day)
            }
            RecordUiState(
                sortEntries(visible, filter.sort),
                attachments.groupBy { it.entryId },
                filter,
                pending,
                loading.value,
                inSelection,
                ids,
            )
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

    fun setDay(day: LocalDate?) {
        filters.value = filters.value.copy(day = day)
    }

    fun setStarredOnly(enabled: Boolean) {
        filters.value = filters.value.copy(starredOnly = enabled)
    }

    fun toggleStarred(entry: EntryEntity) {
        viewModelScope.launch { app.journal.toggleStarred(entry) }
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

    /** 直接进入搜索态（桌面快捷方式「搜一搜」用）。已在搜索态时不重复切换。 */
    fun openSearch() {
        if (!filters.value.searchMode) filters.value = filters.value.copy(searchMode = true)
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

    // ---- 多选导出 ----

    fun enterSelection() {
        selecting.value = true
    }

    /** 退出多选时必须清空勾选：否则下次进来还残留上一次的选择，导出内容会多出没勾过的条目。 */
    fun exitSelection() {
        selecting.value = false
        selectedIds.value = emptySet()
    }

    fun toggleSelected(id: Long) {
        selectedIds.value = if (id in selectedIds.value) selectedIds.value - id else selectedIds.value + id
    }

    /** 全选/取消全选当前可见的条目（受筛选影响，只操作看得见的那些）。 */
    fun toggleSelectAllVisible() {
        val visible = uiState.value.entries.map { it.id }.toSet()
        val allSelected = visible.isNotEmpty() && selectedIds.value.containsAll(visible)
        selectedIds.value = if (allSelected) selectedIds.value - visible else selectedIds.value + visible
    }

    /**
     * 取要导出的条目。
     *
     * 直接按 id 回库查，而不是从当前列表里过滤：用户可以先勾几条、再改筛选条件、再勾几条，
     * 只从可见列表取会让先勾的那批静默消失。
     */
    suspend fun selectedForExport(): List<EntryEntity> {
        val ids = selectedIds.value.toList()
        if (ids.isEmpty()) return emptyList()
        return app.database.entryDao().byIdsOnce(ids)
    }
}

/** 条目所属的自然日（本地时区）。筛选与分组必须用同一套换算，否则会出现「筛出来的不在这天」。 */
internal fun entryDay(entry: EntryEntity): LocalDate =
    Instant.ofEpochMilli(entry.createdAt).atZone(ZoneId.systemDefault()).toLocalDate()

/** 置顶恒在前，其余按所选维度排列。 */
internal fun sortEntries(entries: List<EntryEntity>, sort: EntrySort): List<EntryEntity> =
    entries.sortedWith(
        when (sort) {
            EntrySort.NEWEST -> compareByDescending<EntryEntity> { it.isPinned }.thenByDescending { it.createdAt }
            EntrySort.OLDEST -> compareByDescending<EntryEntity> { it.isPinned }.thenBy { it.createdAt }
            EntrySort.UPDATED -> compareByDescending<EntryEntity> { it.isPinned }.thenByDescending { it.updatedAt }
        }
    )
