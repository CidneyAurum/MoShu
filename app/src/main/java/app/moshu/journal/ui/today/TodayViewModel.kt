package app.moshu.journal.ui.today

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.moshu.journal.MoShuApp
import app.moshu.journal.data.Connectivity
import app.moshu.journal.data.db.AttachmentEntity
import app.moshu.journal.data.db.EntryAiState
import app.moshu.journal.data.db.EntryEntity
import app.moshu.journal.data.db.TodoEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
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
    /** 未保存的正文草稿；离开页面或进程被杀后仍能恢复。 */
    val draft: String = "",
    /** 刚刚整理完成的条目 id。界面提示一次后调用 [TodayViewModel.consumeEnriched] 消费掉。 */
    val justEnrichedId: Long? = null,
    /** 无网络时 AI 整理任务不会执行，界面应显示「等待网络」而不是「整理中」。 */
    val online: Boolean = true,
    /** 首次查询返回前为 true，避免冷启动先闪一下「今天还很轻」。 */
    val loading: Boolean = true,
    /** 往年今日：过去几年同一天写下的记录，最近的年份在前。没有内容时为空。 */
    val memories: List<EntryEntity> = emptyList(),
)

@OptIn(ExperimentalCoroutinesApi::class)
class TodayViewModel : ViewModel() {
    private val app = MoShuApp.instance
    private val saving = MutableStateFlow(false)
    private val message = MutableStateFlow("")
    private val loaded = MutableStateFlow(false)
    private val draft = MutableStateFlow("")
    private val enriched = MutableStateFlow<Long?>(null)
    private var draftJob: Job? = null
    /** 上一轮各条目的 aiState，用于识别 pending/running → succeeded 的跃迁。 */
    private var previousStates: Map<Long, String> = emptyMap()

    private val saveState = combine(saving, message) { busy, note -> SaveState(busy, note) }
    private val feed = combine(app.journal.pendingCount, Connectivity.available(app), loaded) { pending, online, isLoaded ->
        Feed(pending, online, isLoaded)
    }
    private val input = combine(saveState, draft, enriched) { save, text, done ->
        Input(save, text, done)
    }
    /** 当天零点。跨过午夜后要重算，否则「今天」会一直停在昨天。 */
    private val dayStart = MutableStateFlow(startOfToday())

    /** 往年今日是「按天」变化的数据，不必随每次写库重查，跟着 dayStart 刷新即可。 */
    private val memories = MutableStateFlow<List<EntryEntity>>(emptyList())

    private data class SaveState(val saving: Boolean = false, val message: String = "")
    private data class Feed(val pendingCount: Int, val online: Boolean, val loaded: Boolean)
    private data class Input(val save: SaveState = SaveState(), val draft: String = "", val enriched: Long? = null)

    private fun startOfToday(): Long = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    init {
        // 只取一次落盘初值：持续订阅会在用户输入之后把较旧的草稿回灌进输入框。
        // 若此刻已有内容（例如刚从外部分享进来），保留它，别用旧草稿盖掉。
        viewModelScope.launch {
            val stored = app.drafts.composerDraft.first()
            if (stored.isNotBlank() && draft.value.isBlank()) draft.value = stored
        }
        // 跟着「今天」一起刷新：跨天后「往年今日」也要换成新的那一天。
        viewModelScope.launch {
            dayStart.collect { memories.value = app.journal.onThisDay() }
        }
    }

    /** 界面回到前台时调用；只有真的跨天才重建查询，避免无谓重订阅。 */
    fun refreshDay() {
        val current = startOfToday()
        if (dayStart.value != current) dayStart.value = current
    }

    val uiState: StateFlow<TodayUiState> = dayStart.flatMapLatest { start ->
        app.database.entryDao().observeToday(start)
            .onEach { entries ->
                loaded.value = true
                detectEnriched(entries)
            }
            .flatMapLatest { entries ->
                combine(
                    app.journal.observeAttachments(entries.map { it.id }),
                    app.database.todoDao().observeActive(),
                    feed,
                    input,
                    memories,
                ) { attachments, todos, feedState, inState, memoryList ->
                    TodayUiState(
                        entries = entries,
                        attachments = attachments.groupBy { it.entryId },
                        activeTodos = todos,
                        pendingCount = feedState.pendingCount,
                        saving = inState.save.saving,
                        message = inState.save.message,
                        draft = inState.draft,
                        justEnrichedId = inState.enriched,
                        online = feedState.online,
                        memories = memoryList,
                        loading = !feedState.loaded,
                    )
                }
            }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TodayUiState())

    /** 发现条目从「等待/整理中」变为「已完成」时记一次，界面据此给出成功反馈。 */
    private fun detectEnriched(entries: List<EntryEntity>) {
        if (previousStates.isNotEmpty()) {
            entries.firstOrNull { entry ->
                entry.aiState == EntryAiState.SUCCEEDED.value && previousStates[entry.id] in BUSY_STATES
            }?.let { enriched.value = it.id }
        }
        previousStates = entries.associate { it.id to it.aiState }
    }

    fun consumeEnriched() { enriched.value = null }

    fun onDraftChange(value: String) {
        draft.value = value
        // 防抖：逐字符写 DataStore 会在长句上产生大量磁盘写入。
        draftJob?.cancel()
        draftJob = viewModelScope.launch {
            delay(DRAFT_DEBOUNCE_MS)
            app.drafts.saveComposerDraft(value)
        }
    }

    private fun clearDraft() {
        draftJob?.cancel()
        draft.value = ""
        viewModelScope.launch { app.drafts.saveComposerDraft("") }
    }

    fun add(content: String, images: List<Uri>, onSaved: () -> Unit = {}) {
        if (saving.value || (content.isBlank() && images.isEmpty())) return
        viewModelScope.launch {
            saving.value = true
            message.value = ""
            try {
                app.journal.addEntry(content, images)
                clearDraft()
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

    fun toggleStarred(entry: EntryEntity) {
        viewModelScope.launch { app.journal.toggleStarred(entry) }
    }

    fun togglePinned(entry: EntryEntity) {
        viewModelScope.launch { app.journal.togglePinned(entry) }
    }

    fun duplicate(entry: EntryEntity) {
        viewModelScope.launch { app.journal.duplicateEntry(entry.id) }
    }

    /** 删除走可撤销路径：仓库保留附件文件到撤销窗口结束，撤销时重建数据行。 */
    fun delete(entry: EntryEntity) {
        viewModelScope.launch {
            val deleted = app.journal.deleteEntry(entry.id) ?: return@launch
            app.notices.post("已删除这条记忆", "撤销") {
                viewModelScope.launch { app.journal.restoreEntry(deleted) }
            }
        }
    }

    fun clearMessage() { message.value = "" }

    private companion object {
        const val DRAFT_DEBOUNCE_MS = 300L
        val BUSY_STATES = setOf(EntryAiState.PENDING.value, EntryAiState.RUNNING.value)
    }
}