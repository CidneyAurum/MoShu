package app.moshu.journal.ui.calendar

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.moshu.journal.data.db.EntryEntity
import app.moshu.journal.data.db.EventEntity
import app.moshu.journal.data.db.RepeatRule
import app.moshu.journal.data.db.eventDay
import app.moshu.journal.data.db.eventTime
import app.moshu.journal.data.schedule.EventIntentParser
import app.moshu.journal.ui.components.EntryCard
import app.moshu.journal.ui.components.MoShuEmptyState
import app.moshu.journal.ui.components.MoShuMessageBar
import app.moshu.journal.ui.components.MoShuPageHeader
import app.moshu.journal.ui.components.MoShuSectionTitle
import app.moshu.journal.ui.components.MessageSeverity
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * 日历：月网格 + 点某天看当天的安排与记录。
 *
 * 日历和「回顾」页的分工：回顾看的是趋势（心情曲线、活跃热力），
 * 这里看的是**具体某天要做什么**——计划与记录放在同一个视图里对照，
 * 才能回答「我本来打算做什么、实际做了什么」。
 */
@Composable
fun CalendarScreen(
    viewModel: CalendarViewModel,
    onOpenEntry: (Long) -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * 从外部分享进来的一句话（例如在微信里长按「这周五八点去上实验课」→ 分享到墨枢）。
     * 预填到输入栏并自动解析一次，省掉重新打一遍。
     */
    sharedPlanText: String = "",
    onSharedConsumed: () -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var editing by remember { mutableStateOf<EventEntity?>(null) }
    var creating by remember { mutableStateOf(false) }
    /** 解析结果不满意时带进完整编辑器。 */
    var manualDraft by remember { mutableStateOf<EventIntentParser.Intent?>(null) }
    val listState = rememberLazyListState()
    // 月网格默认展开，列表一往上滚就收起成一周；滚回顶部自动展开。
    // 用户手动点过开关之后以他的选择为准，直到列表回到顶部——否则「我明明收起来了，
    // 手一滑它又弹开」会让人以为开关坏了。
    var manualExpanded by rememberSaveable { mutableStateOf<Boolean?>(null) }
    val atTop by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0 } }
    LaunchedEffect(atTop) { if (atTop) manualExpanded = null }
    val collapsed = manualExpanded?.not() ?: !atTop

    Column(modifier.fillMaxSize()) {
        MoShuPageHeader(
            title = "日历",
            subtitle = "${state.month.year}年${state.month.monthValue}月 · 当月 ${state.countsByDay.values.sum()} 项安排",
            onSettings = onSettings,
            actions = {
                TextButton(onClick = viewModel::goToday, modifier = Modifier.heightIn(min = 48.dp)) { Text("今天") }
                IconButton(onClick = { creating = true }) { Icon(Icons.Rounded.Add, "新建安排") }
            },
        )

        if (state.message.isNotBlank()) {
            MoShuMessageBar(state.message, Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        }
        // 精确闹钟没授权时如实告知：否则用户会以为 14:30 的提醒一定准时。
        if (!state.exactAlarmAllowed) {
            MoShuMessageBar(
                "系统未授予「精确闹钟」权限，事件提醒可能延迟十几分钟。",
                Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                MessageSeverity.WARN,
                // 只告知不给出路，用户只能自己翻系统设置；这里直接送到那一页。
                actionLabel = "去授权",
                onAction = { openExactAlarmSettings(context) },
            )
        }

        // 一句话安排：自然语言输入。放在最上面，因为这是最快的路径。
        QuickPlanBar(
            planning = state.planning,
            error = state.planningError,
            onSubmit = viewModel::planFromText,
            initialText = sharedPlanText,
        )
        // 分享进来的句子直接解析一次，用户只需核对确认。
        LaunchedEffect(sharedPlanText) {
            if (sharedPlanText.isNotBlank()) {
                viewModel.planFromText(sharedPlanText)
                onSharedConsumed()
            }
        }

        state.pendingIntent?.let { intent ->
            IntentConfirmCard(
                intent = intent,
                fromAi = state.pendingFromAi,
                onConfirm = { soundUri, soundLabel, importance ->
                    viewModel.confirmIntent(intent, soundUri, soundLabel, importance)
                },
                onDismiss = viewModel::dismissIntent,
                onEditManually = {
                    // 解析不准时，带着已解析出的日期时间打开完整编辑器让用户改，
                    // 而不是让用户从头再填一遍。
                    manualDraft = intent
                    viewModel.dismissIntent()
                },
            )
        }

        MonthGrid(
            month = state.month,
            selected = state.selectedDay,
            countsByDay = state.countsByDay,
            collapsed = collapsed,
            onToggleCollapsed = { manualExpanded = collapsed },
            onPrev = { viewModel.showMonth(state.month.minusMonths(1)) },
            onNext = { viewModel.showMonth(state.month.plusMonths(1)) },
            onSelect = viewModel::selectDay,
        )

        if (state.loading) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            }
            return@Column
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                MoShuSectionTitle(
                    "${state.selectedDay.format(DateTimeFormatter.ofPattern("M月d日 EEEE", Locale.CHINA))}的安排",
                    action = if (state.events.isEmpty()) "添加" else null,
                    onAction = { creating = true },
                )
            }
            if (state.events.isEmpty()) {
                item {
                    MoShuEmptyState(
                        "这天还没有安排",
                        "点右上角「+」加一件事：可以设日期时间、重复规则，也能单独挑一个提醒铃声。",
                        icon = Icons.Rounded.Event,
                    )
                }
            } else {
                items(state.events, key = { it.id }) { event ->
                    EventRow(
                        event = event,
                        onToggleDone = { viewModel.toggleDone(event) },
                        onEdit = { editing = event },
                        onDelete = { viewModel.delete(event) },
                    )
                }
            }

            if (state.entries.isNotEmpty()) {
                item { MoShuSectionTitle("这天的记录 · ${state.entries.size}") }
                items(state.entries, key = { "entry-${it.id}" }) { entry ->
                    EntryCard(
                        entry = entry,
                        onClick = { onOpenEntry(entry.id) },
                        attachments = state.attachments[entry.id].orEmpty(),
                    )
                }
            }
        }
    }

    if (creating || manualDraft != null) {
        EventEditorSheet(
            initialDay = manualDraft?.day ?: state.selectedDay,
            initialIntent = manualDraft,
            initialEvent = null,
            onDismiss = { creating = false },
            onSave = { draft ->
                viewModel.create(
                    title = draft.title,
                    note = draft.note,
                    day = draft.day,
                    minuteOfDay = draft.minuteOfDay,
                    allDay = draft.allDay,
                    repeat = draft.repeat,
                    reminderOffsetMin = draft.reminderOffsetMin,
                    soundUri = draft.soundUri,
                    soundLabel = draft.soundLabel,
                    importance = draft.importance,
                )
                creating = false
                manualDraft = null
            },
        )
    }
}

/**
 * 一句话安排：自然语言输入栏。
 *
 * 放在日历页最上方，因为对绝大多数场景它是最快的路径——
 * 说「这周五八点去上实验课」比「点 +、选日期、选时间、填标题」快得多。
 * 解析结果一律先给确认卡片，不直接落库。
 */
@Composable
private fun QuickPlanBar(
    planning: Boolean,
    error: String,
    onSubmit: (String) -> Unit,
    initialText: String = "",
) {
    var text by remember { mutableStateOf(initialText) }
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "说一句，我来安排",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("这周五八点去上实验课") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.large,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        if (text.isNotBlank() && !planning) {
                            onSubmit(text)
                            text = ""
                        }
                    }),
                )
                Spacer(Modifier.width(8.dp))
                if (planning) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                } else {
                    IconButton(
                        onClick = { if (text.isNotBlank()) { onSubmit(text); text = "" } },
                        enabled = text.isNotBlank(),
                    ) { Icon(Icons.AutoMirrored.Rounded.Send, "解析并安排") }
                }
            }
            Text(
                "会读出日期、时间与提前量，确认后才会加入日历。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (error.isNotBlank()) {
                Text(error, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

/**
 * 解析确认卡片。
 *
 * **必须让用户核对再落库**：把「下周五」听成「本周五」会让人错过事情，
 * 比不安排更糟。卡片把「读到了什么」和「从哪句话读出来的」都摆出来，
 * 并对不确定的点（比如「八点」是上午还是晚上）明确提示。
 */
@Composable
private fun IntentConfirmCard(
    intent: EventIntentParser.Intent,
    fromAi: Boolean,
    onConfirm: (soundUri: String, soundLabel: String, importance: String) -> Unit,
    onDismiss: () -> Unit,
    onEditManually: () -> Unit,
) {
    var soundUri by remember { mutableStateOf("") }
    var soundLabel by remember { mutableStateOf("") }
    var pickingSound by remember { mutableStateOf(false) }
    val zone = remember { java.time.ZoneId.systemDefault() }
    val day = intent.day ?: return

    val timeText = when {
        intent.allDay || intent.time == null -> "全天"
        else -> intent.time.format(DateTimeFormatter.ofPattern("HH:mm"))
    }
    val reminderText = when {
        intent.reminderOffsetMin == EventEntity.NO_REMINDER -> "不提醒"
        intent.reminderOffsetMin == 0 -> "准点提醒"
        intent.reminderOffsetMin % 60 == 0 -> "提前 ${intent.reminderOffsetMin / 60} 小时提醒"
        else -> "提前 ${intent.reminderOffsetMin} 分钟提醒"
    }

    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("请核对", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onTertiaryContainer)
            Text(intent.title, style = MaterialTheme.typography.titleMedium)
            Text(
                buildString {
                    append(day.format(DateTimeFormatter.ofPattern("M月d日 EEEE", Locale.CHINA)))
                    append(" · ").append(timeText)
                    append(" · ").append(reminderText)
                    if (intent.repeat != RepeatRule.NONE) append(" · ").append(RepeatRule.label(intent.repeat))
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            if (soundUri.isNotBlank()) {
                Text(
                    "铃声：${soundLabel.ifBlank { "自定义铃声" }}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                buildString {
                    if (fromAi) append("由 AI 理解") else append("本地解析")
                    if (intent.matchedDateText.isNotBlank()) append(" · 日期来自「${intent.matchedDateText}」")
                    if (intent.matchedTimeText.isNotBlank()) append(" · 时间来自「${intent.matchedTimeText}」")
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { onConfirm(soundUri, soundLabel, "default") }) { Text("加入日历") }
                TextButton(onClick = { pickingSound = true }) { Text(if (soundUri.isBlank()) "选铃声" else "换铃声") }
                TextButton(onClick = onEditManually) { Text("改一下") }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        }
    }

    if (pickingSound) {
        EventSoundPicker(
            current = soundUri,
            onPick = { uri, label -> soundUri = uri; soundLabel = label; pickingSound = false },
            onDismiss = { pickingSound = false },
        )
    }
}

/**
 * 月网格。
 *
 * 用「周一起始」而不是系统默认：中文习惯周一是一周第一天，
 * 用周日起始会让「这周」的直觉对不上。
 *
 * 可以收起成一行：一个月 6 行格子会把当天的安排挤到屏幕外，
 * 而「今天要做什么」才是这一页真正要回答的问题。
 * 收起时保留「选中那天所在的那一周」，而不是永远显示第一周——
 * 看下半月的日子时显示第一周等于什么都没显示。
 */
@Composable
private fun MonthGrid(
    month: YearMonth,
    selected: LocalDate,
    countsByDay: Map<LocalDate, Int>,
    collapsed: Boolean,
    onToggleCollapsed: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onSelect: (LocalDate) -> Unit,
) {
    val today = LocalDate.now()
    val firstDay = month.atDay(1)
    // DayOfWeek.value：周一=1 … 周日=7，减 1 得到「本月 1 号前要空几格」。
    val leading = firstDay.dayOfWeek.value - 1
    val days = month.lengthOfMonth()
    val cells = leading + days
    val rows = (cells + 6) / 7
    val selectedRow = (leading + selected.dayOfMonth - 1) / 7

    // 格子高度固定而不是 aspectRatio(1f)：正方格在手机上每行近 50dp，
    // 六行就是小半个屏幕，正是「日历占了大半界面」的来源。
    val rowHeight = 40.dp
    val rowGap = 2.dp
    val firstVisibleRow = if (collapsed) selectedRow else 0
    val visibleRows = if (collapsed) 1 else rows
    val visibleHeight by animateDpAsState(
        rowHeight * visibleRows + rowGap * (visibleRows - 1),
        label = "月网格高度",
    )

    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onPrev) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "上个月") }
                Text(
                    "${month.year}年${month.monthValue}月",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                // 展开/收起不只是滚动联动的副作用，也要能直接点。
                IconButton(onClick = onToggleCollapsed) {
                    Icon(
                        if (collapsed) Icons.Rounded.ExpandMore else Icons.Rounded.ExpandLess,
                        contentDescription = if (collapsed) "展开整月" else "收起为一周",
                    )
                }
                IconButton(onClick = onNext) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "下个月") }
            }
            Row(Modifier.fillMaxWidth()) {
                listOf("一", "二", "三", "四", "五", "六", "日").forEach { label ->
                    Text(
                        label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            // 收起时只渲染「选中那天所在的那一周」，容器高度做动画。
            // 不用「渲染全部再位移裁切」：那样内层要显式超出父容器，约束一层层传下去很容易压扁，
            // 实测会得到一片空白。少渲染几行也更省。
            Box(Modifier.fillMaxWidth().height(visibleHeight).clipToBounds()) {
                Column(verticalArrangement = Arrangement.spacedBy(rowGap)) {
                    for (row in firstVisibleRow until firstVisibleRow + visibleRows) {
                        Row(Modifier.fillMaxWidth()) {
                            for (col in 0 until 7) {
                                val index = row * 7 + col
                                val dayNumber = index - leading + 1
                                if (dayNumber < 1 || dayNumber > days) {
                                    Spacer(Modifier.weight(1f).height(rowHeight))
                                } else {
                                    val day = month.atDay(dayNumber)
                                    DayCell(
                                        day = day,
                                        count = countsByDay[day] ?: 0,
                                        isSelected = day == selected,
                                        isToday = day == today,
                                        onClick = { onSelect(day) },
                                        modifier = Modifier.weight(1f).height(rowHeight),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DayCell(
    day: LocalDate,
    count: Int,
    isSelected: Boolean,
    isToday: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bg = when {
        isSelected -> MaterialTheme.colorScheme.primary
        isToday -> MaterialTheme.colorScheme.primaryContainer
        else -> androidx.compose.ui.graphics.Color.Transparent
    }
    val fg = when {
        isSelected -> MaterialTheme.colorScheme.onPrimary
        else -> MaterialTheme.colorScheme.onSurface
    }
    Box(
        modifier = modifier
            .padding(horizontal = 3.dp)
            .background(bg, CircleShape)
            .clickable(onClickLabel = "查看 ${day.monthValue}月${day.dayOfMonth}日 的安排", onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "${day.dayOfMonth}",
                style = MaterialTheme.typography.bodyMedium,
                color = fg,
                fontWeight = if (isToday || isSelected) FontWeight.Bold else FontWeight.Normal,
            )
            // 有安排的日子打点，最多三个：再多也数不清，只表达「不少」。
            if (count > 0) {
                Row(horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                    repeat(count.coerceAtMost(3)) {
                        Box(
                            Modifier.size(3.dp).background(
                                if (isSelected) MaterialTheme.colorScheme.onPrimary
                                else MaterialTheme.colorScheme.primary,
                                CircleShape,
                            ),
                        )
                    }
                }
            } else {
                Spacer(Modifier.size(3.dp))
            }
        }
    }
}

@Composable
private fun EventRow(
    event: EventEntity,
    onToggleDone: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClickLabel = "编辑安排", onClick = onEdit),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onToggleDone) {
                Icon(
                    if (event.done) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                    if (event.done) "标记为未完成" else "标记为已完成",
                    tint = if (event.done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (event.allDay) "全天" else event.eventTime().format(DateTimeFormatter.ofPattern("HH:mm")),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        event.title,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textDecoration = if (event.done) androidx.compose.ui.text.style.TextDecoration.LineThrough else null,
                    )
                }
                if (event.note.isNotBlank()) {
                    Text(
                        event.note.replace('\n', ' '),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (event.repeatRule != RepeatRule.NONE) {
                        MetaChip(Icons.Rounded.Repeat, RepeatRule.label(event.repeatRule))
                    }
                    if (event.hasReminder) {
                        val offset = event.reminderOffsetMin
                        val label = when {
                            offset == 0 -> "准点提醒"
                            offset % 60 == 0 -> "提前 ${offset / 60} 小时"
                            else -> "提前 $offset 分钟"
                        }
                        // 自定义铃声要显示出来：用户设了不同铃声就是为了区分事件。
                        val sound = if (event.soundUri.isBlank()) label
                        else "$label · ${event.soundLabel.ifBlank { "自定义铃声" }}"
                        MetaChip(Icons.Rounded.NotificationsActive, sound)
                    }
                }
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Rounded.DeleteOutline, "删除安排", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun MetaChip(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(12.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(3.dp))
        Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * 跳到系统的「精确闹钟」授权页。
 *
 * Android 12+ 起 SCHEDULE_EXACT_ALARM 默认不授予，且不能在应用内弹窗申请，
 * 只能引导用户到系统设置。不同厂商 ROM 的入口不一致，所以依次尝试几个已知 action，
 * 都失败时退回本应用的详情页——至少不会点了没反应。
 */
private fun openExactAlarmSettings(context: android.content.Context) {
    val candidates = buildList {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            add(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
        }
        add(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
    }
    for (action in candidates) {
        val intent = android.content.Intent(action).apply {
            if (action == android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS) {
                data = android.net.Uri.fromParts("package", context.packageName, null)
            } else {
                data = android.net.Uri.fromParts("package", context.packageName, null)
            }
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val ok = runCatching { context.startActivity(intent) }.isSuccess
        if (ok) return
    }
}
