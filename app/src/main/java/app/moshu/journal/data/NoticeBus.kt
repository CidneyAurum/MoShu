package app.moshu.journal.data

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 一条待展示的页面提示。action 非空时 snackbar 会给出对应按钮（例如「撤销」「查看」）。
 */
data class UiNotice(
    val message: String,
    val actionLabel: String? = null,
    val action: (() -> Unit)? = null,
)

/**
 * 跨页面的提示总线。删除可能发生在详情页、记忆页或行动页，而 snackbar 只在主界面有一个宿主，
 * 因此用一条进程级事件流承接，避免每个页面各写一套 SnackbarHost。
 * tryEmit + 有缓冲：推送发生在 ViewModel 的主线程协程里，不会因为暂时没有订阅者而丢失。
 */
class NoticeBus {
    private val _events = MutableSharedFlow<UiNotice>(extraBufferCapacity = 8)
    val events: SharedFlow<UiNotice> = _events.asSharedFlow()

    fun post(message: String, actionLabel: String? = null, action: (() -> Unit)? = null) {
        _events.tryEmit(UiNotice(message, actionLabel, action))
    }
}