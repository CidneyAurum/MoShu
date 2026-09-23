package app.moshu.journal.ui.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.moshu.journal.MoShuApp
import app.moshu.journal.data.db.AttachmentEntity
import app.moshu.journal.data.db.EntryEntity
import app.moshu.journal.data.db.EventEntity
import app.moshu.journal.data.db.RepeatRule
import app.moshu.journal.data.db.eventDay
import app.moshu.journal.data.schedule.EventIntentParser
import app.moshu.journal.data.schedule.AiEventPlanner
import app.moshu.journal.reminder.EventReminderScheduler
import app.moshu.journal.reminder.Notifications
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

data class CalendarUiState(
    val month: YearMonth = YearMonth.now(),
    val selectedDay: LocalDate = LocalDate.now(),
    /** 当月每天有几条安排，用于网格上的标记。 */
    val countsByDay: Map<LocalDate, Int> = emptyMap(),
    /** 选中那天的安排。 */
    val events: List<EventEntity> = emptyList(),
    /** 选中那天的记忆，便于「当天计划 + 当天记录」对照着看。 */
    val entries: List<app.moshu.journal.data.db.EntryEntity> = emptyList(),
    val attachments: Map<Long, List<AttachmentEntity>> = emptyMap(),
    /** 系统未授予精确闹钟权限时提示用户「提醒可能延迟」。 */
    val exactAlarmAllowed: Boolean = true,
    /** 待用户确认的解析结果；非空时显示确认卡片。 */
    val pendingIntent: EventIntentParser.Intent? = null,
    /** 该结果是否由 AI 解析（界面据此说明「用了模型」）。 */
    val pendingFromAi: Boolean = false,
    /** 正在解析。 */
    val planning: Boolean = false,
    /** 解析失败原因。 */
    val planningError: String = "",
    val message: String = "",
    val loading: Boolean = true,
)

@OptIn(ExperimentalCoroutinesApi::class)
class CalendarViewModel : ViewModel() {
    private val app = MoShuApp.instance
    private val zone = ZoneId.systemDefault()

    private val month = MutableStateFlow(YearMonth.now())
    private val selected = MutableStateFlow(LocalDate.now())
    private val message = MutableStateFlow("")
    private val loaded = MutableStateFlow(false)
    private val exactAllowed = MutableStateFlow(true)

    /** 待确认的解析结果。 */
    private val pendingIntent = MutableStateFlow<EventIntentParser.Intent?>(null)
    private val pendingFromAi = MutableStateFlow(false)
    private val planning = MutableStateFlow(false)
    private val planningError = MutableStateFlow("")

    private fun startOfDay(day: LocalDate): Long = day.atStartOfDay(zone).toInstant().toEpochMilli()

    /** 当月的安排。整月一次取回，避免网格逐天查询。 */
    private val monthEvents = month.flatMapLatest { ym ->
        val from = startOfDay(ym.atDay(1))
        val to = startOfDay(ym.plusMonths(1).atDay(1))
        app.database.eventDao().observeRange(from, to)
    }

    private val dayEvents = selected.flatMapLatest { day ->
        app.database.eventDao().observeBetween(startOfDay(day), startOfDay(day.plusDays(1)))
    }

    private val dayEntries = selected.flatMapLatest { day ->
        app.database.entryDao().observeBetween(startOfDay(day), startOfDay(day.plusDays(1)))
            .also { loaded.value = true }
    }

    /** 当天记录的缩略图。列表里要显示，否则「当天安排」看不到配图。 */
    private val dayAttachments = dayEntries.flatMapLatest { entries ->
        app.journal.observeAttachments(entries.map { it.id })
    }

    val uiState: StateFlow<CalendarUiState> = combine(
        month, selected, monthEvents, dayEvents, dayEntries, dayAttachments, message, exactAllowed,
        pendingIntent, pendingFromAi, planning, planningError,
    ) { values ->
        CalendarUiState(
            month = values[0] as YearMonth,
            selectedDay = values[1] as LocalDate,
            countsByDay = (values[2] as List<EventEntity>).groupingBy { it.eventDay(zone) }.eachCount(),
            events = values[3] as List<EventEntity>,
            entries = values[4] as List<EntryEntity>,
            attachments = (values[5] as List<AttachmentEntity>).groupBy { it.entryId },
            message = values[6] as String,
            exactAlarmAllowed = values[7] as Boolean,
            pendingIntent = values[8] as EventIntentParser.Intent?,
            pendingFromAi = values[9] as Boolean,
            planning = values[10] as Boolean,
            planningError = values[11] as String,
            loading = !loaded.value,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CalendarUiState())

    init {
        refreshPermissionState()
    }

    fun refreshPermissionState() {
        exactAllowed.value = EventReminderScheduler.canScheduleExact(app)
    }

    fun showMonth(target: YearMonth) {
        month.value = target
        // 换月时把选中日拉到该月：否则右侧列表明明换了月份却还在显示旧日期。
        if (YearMonth.from(selected.value) != target) {
            val today = LocalDate.now()
            selected.value = if (YearMonth.from(today) == target) today else target.atDay(1)
        }
    }

    fun selectDay(day: LocalDate) {
        selected.value = day
        if (YearMonth.from(day) != month.value) month.value = YearMonth.from(day)
    }

    fun goToday() {
        val today = LocalDate.now()
        month.value = YearMonth.from(today)
        selected.value = today
    }

    fun clearMessage() { message.value = "" }

    /**
     * 用一句话解析日程。
     *
     * 先跑本地解析器（离线、瞬时、确定）；本地读不出日期才交给 AI。
     * 结果只放进 [pendingIntent] 让用户核对，**不直接落库**——
     * 把「下周五」听成「本周五」比不安排更糟。
     */
    fun planFromText(text: String) {
        val value = text.trim()
        if (value.isEmpty()) return
        viewModelScope.launch {
            planning.value = true
            planningError.value = ""
            val now = java.time.LocalDateTime.now()
            val local = EventIntentParser.parse(value, now, zone)
            val outcome = AiEventPlanner.plan(
                text = value,
                config = app.settings.currentAiConfig(),
                now = now,
                zone = zone,
                fallback = local,
            )
            planning.value = false
            if (outcome.intent.day == null) {
                planningError.value = outcome.error.ifBlank {
                    "没读出日期。可以写得更明确些，比如「这周五八点去上实验课」。"
                }
                return@launch
            }
            pendingIntent.value = outcome.intent
            pendingFromAi.value = outcome.fromAi
        }
    }

    fun dismissIntent() {
        pendingIntent.value = null
        pendingFromAi.value = false
    }

    /** 用户核对后确认落库。 */
    fun confirmIntent(intent: EventIntentParser.Intent, soundUri: String, soundLabel: String, importance: String) {
        val event = AiEventPlanner.toEntity(intent, zone) ?: return
        viewModelScope.launch {
            val saved = event.copy(soundUri = soundUri, soundLabel = soundLabel, importance = importance)
            val id = app.database.eventDao().insert(saved)
            val stored = saved.copy(id = id)
            if (stored.hasReminder) {
                Notifications.ensureEventChannel(app, stored.soundUri, stored.soundLabel, stored.importance)
                EventReminderScheduler.schedule(app, stored)
            }
            message.value = "已安排「${stored.title}」，将在 ${describeTrigger(stored)} 提醒"
            dismissIntent()
            // 跳到该事件所在的那一天，让用户立刻看到结果。
            selectDay(java.time.Instant.ofEpochMilli(stored.startAt).atZone(zone).toLocalDate())
            refreshPermissionState()
        }
    }

    /** 新建安排。默认落在选中那天的当前整点，减少一次改时间的操作。 */
    fun create(title: String, note: String, day: LocalDate, minuteOfDay: Int, allDay: Boolean,
               repeat: String, reminderOffsetMin: Int, soundUri: String, soundLabel: String, importance: String) {
        val text = title.trim()
        if (text.isEmpty()) return
        viewModelScope.launch {
            val startAt = if (allDay) startOfDay(day)
            else day.atStartOfDay(zone).plusMinutes(minuteOfDay.toLong()).toInstant().toEpochMilli()
            val event = EventEntity(
                title = text,
                note = note.trim(),
                startAt = startAt,
                allDay = allDay,
                repeatRule = repeat,
                reminderOffsetMin = reminderOffsetMin,
                soundUri = soundUri,
                soundLabel = soundLabel,
                importance = importance,
            )
            val id = app.database.eventDao().insert(event)
            val saved = event.copy(id = id)
            // 保存时就把通知渠道建好，而不是等提醒响了才建：
            // 渠道声音在创建后不可改，提前建出来用户能在系统设置里看到并调整；
            // 万一铃声 URI 有问题，也在保存这一刻暴露，而不是到点才发现没声音。
            if (saved.hasReminder) {
                Notifications.ensureEventChannel(app, saved.soundUri, saved.soundLabel, saved.importance)
            }
            val scheduled = if (saved.hasReminder) EventReminderScheduler.schedule(app, saved) else false
            message.value = buildString {
                append("已添加「").append(text).append('」')
                when {
                    !saved.hasReminder -> append("（未开启提醒）")
                    scheduled -> append("，将在 ")
                        .append(describeTrigger(saved))
                        .append(" 提醒")
                    else -> append("，但提醒时间已过，未排提醒")
                }
            }
            refreshPermissionState()
        }
    }

    fun toggleDone(event: EventEntity) {
        viewModelScope.launch {
            val updated = event.copy(done = !event.done, updatedAt = System.currentTimeMillis())
            app.database.eventDao().update(updated)
            if (updated.done) {
                EventReminderScheduler.cancel(app, updated)
            } else if (updated.hasReminder) {
                Notifications.ensureEventChannel(app, updated.soundUri, updated.soundLabel, updated.importance)
                EventReminderScheduler.schedule(app, updated)
            }
        }
    }

    fun delete(event: EventEntity) {
        viewModelScope.launch {
            EventReminderScheduler.cancel(app, event)
            app.database.eventDao().deleteById(event.id)
            message.value = "已删除「${event.title}」"
        }
    }

    /** 把触发时刻说成人话，用户才能确认自己设对了。 */
    private fun describeTrigger(event: EventEntity): String {
        val at = event.remindAt ?: return "稍后"
        val fmt = java.time.format.DateTimeFormatter.ofPattern("M月d日 HH:mm", java.util.Locale.CHINA)
        val text = java.time.Instant.ofEpochMilli(at).atZone(zone).format(fmt)
        return if (event.repeatRule == RepeatRule.NONE) text else "${RepeatRule.label(event.repeatRule)} $text"
    }
}
