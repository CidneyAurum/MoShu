package app.moshu.journal.ui.tags

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.moshu.journal.MoShuApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class TagsUiState(
    val tags: List<Pair<String, Int>> = emptyList(),
    val loading: Boolean = true,
    val message: String = "",
    val error: Boolean = false,
)

/**
 * 标签管理。
 *
 * 标签在此之前只能由 AI 写进 `tagsJson`，用户既改不了错字也合并不了同义标签
 * （「加班」和「加班中」会各占一行），时间一长标签体系就废了。
 */
class TagsViewModel : ViewModel() {
    private val app = MoShuApp.instance
    private val state = MutableStateFlow(TagsUiState())
    val uiState: StateFlow<TagsUiState> = state.asStateFlow()

    init { reload() }

    fun reload() {
        viewModelScope.launch {
            val tags = app.journal.tagCounts()
            state.value = state.value.copy(tags = tags, loading = false)
        }
    }

    fun clearMessage() { state.value = state.value.copy(message = "") }

    fun rename(from: String, to: String) {
        val target = to.trim()
        when {
            target.isBlank() -> return
            target == from -> return
        }
        viewModelScope.launch {
            val changed = app.journal.renameTag(from, target)
            state.value = state.value.copy(
                message = if (changed == 0) "「$from」没有匹配到任何条目" else "已把「$from」重命名为「$target」，更新 $changed 条",
                error = changed == 0,
            )
            reload()
        }
    }

    /** 合并到已存在的标签：两边条目合到一起，源标签消失。 */
    fun merge(from: String, into: String) {
        if (from == into || into.isBlank()) return
        viewModelScope.launch {
            val changed = app.journal.mergeTag(from, into.trim())
            state.value = state.value.copy(message = "已把「$from」并入「$into」，更新 $changed 条")
            reload()
        }
    }

    /** 删除标签只从条目上摘掉，不删除条目本身。 */
    fun delete(tag: String) {
        viewModelScope.launch {
            val changed = app.journal.deleteTag(tag)
            state.value = state.value.copy(message = "已从 $changed 条记录上移除「$tag」，记录本身没有删除")
            reload()
        }
    }
}
