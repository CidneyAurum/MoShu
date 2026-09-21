package app.moshu.journal.ui.today

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.moshu.journal.MoShuApp
import app.moshu.journal.data.Connectivity
import app.moshu.journal.data.db.AttachmentEntity
import app.moshu.journal.data.db.EntryEntity
import app.moshu.journal.data.db.TodoEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.onEach
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
    val message: String = "",
    /** 无网络时 AI 整理任务不会执行，界面应显示「等待网络」而不是「整理中」。 */
    val online: Boolean = true,
    /** 首次查询返回前为 true，避免冷启动先闪一下「今天还很轻」。 */
    val loading: Boolean = true,
)

@OptIn(ExperimentalCoroutinesApi::class)
class TodayViewModel : ViewModel() {
    private val app = MoShuApp.instance
    private val saving = MutableStateFlow(false)
    private val message = MutableStateFlow("")
    private val loaded = MutableStateFlow(false)
    private val saveState = combine(saving, message) { busy, note -> SaveState(busy, note) }
    private val feed = combine(app.journal.pendingCount, Connectivity.available(app), loaded) { pending, online, isLoaded ->
        Feed(pending, online, isLoaded)
    }

    /** 当天零点。跨过午夜后要重算，否则「今天」会一直停在昨天。 */
    private val dayStart = MutableStateFlow(startOfToday())

    private data class SaveState(val saving: Boolean = false, val message: String = "")
    private data class Feed(val pendingCount: Int, val online: Boolean, val loaded: Boolean)

    private fun startOfToday(): Long = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    /** 界面回到前台时调用；只有真的跨天才重建查询，避免无谓重订阅。 */
    fun refreshDay() {
        val current = startOfToday()
        if (dayStart.value != current) dayStart.value = current
    }

    val uiState: StateFlow<TodayUiState> = dayStart.flatMapLatest { start ->
        app.database.entryDao().observeToday(start).onEach { loaded.value = true }.flatMapLatest { entries ->
            combine(
                app.journal.observeAttachments(entries.map { it.id }),
                app.database.todoDao().observeActive(),
                feed,
                saveState,
            ) { attachments, todos, feedState, save ->
                TodayUiState(
                    entries = entries,
                    attachments = attachments.groupBy { it.entryId },
                    activeTodos = todos,
                    pendingCount = feedState.pendingCount,
                    saving = save.saving,
                    message = save.message,
                    online = feedState.online,
                    loading = !feedState.loaded,
                )
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TodayUiState())

    fun add(content: String, images: List<Uri>, onSaved: () -> Unit = {}) {
        if (saving.value || (content.isBlank() && images.isEmpty())) return
        viewModelScope.launch {
            saving.value = true
            message.value = ""
            try {
                app.journal.addEntry(content, images)
                onSaved()
            } catch (error: Throwable) {
                // addEntry 在图片导入失败时会回滚并抛出，这里必须接住，否则异常会逃出
                // viewModelScope 直接终止进程。
                message.value = "保存失败：${error.message ?: "请换一张图片再试"}"
            } finally {
                saving.value = false
            }
        }
    }

    fun clearMessage() { message.value = "" }
}
