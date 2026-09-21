package app.moshu.journal.ui.trash

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.moshu.journal.MoShuApp
import app.moshu.journal.data.JournalRepository
import app.moshu.journal.data.db.EntryEntity
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class TrashUiState(
    val entries: List<EntryEntity> = emptyList(),
    val loading: Boolean = true,
)

/** 回收站：删除不再等于销毁，保留期内随时可以拿回来。 */
class TrashViewModel : ViewModel() {
    private val app = MoShuApp.instance

    val uiState: StateFlow<TrashUiState> = app.database.entryDao().observeTrash()
        .map { TrashUiState(it, false) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TrashUiState())

    fun restore(entry: EntryEntity) {
        viewModelScope.launch {
            if (app.journal.restoreFromTrash(entry.id)) {
                app.notices.post("已恢复这条记忆")
            }
        }
    }

    /** 彻底删除：这次连图片文件一起清掉，不可撤销，所以调用方必须先确认。 */
    fun purge(entry: EntryEntity) {
        viewModelScope.launch {
            app.journal.purgeEntry(entry.id)
            app.notices.post("已彻底删除")
        }
    }

    fun purgeAll() {
        viewModelScope.launch {
            val count = app.journal.purgeAllTrash()
            app.notices.post(if (count > 0) "已清空回收站（$count 条）" else "回收站已经是空的")
        }
    }

    /** 保留期天数，用于界面说明。 */
    val retentionDays: Int get() = (JournalRepository.TRASH_RETENTION_MS / (24 * 60 * 60 * 1000)).toInt()
}
