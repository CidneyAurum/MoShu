package app.moshu.journal.ui.insights

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.moshu.journal.data.db.AiReviewEntity
import app.moshu.journal.data.MoodTagLink
import app.moshu.journal.data.WritingStats
import app.moshu.journal.data.db.Category
import app.moshu.journal.ui.components.MoShuConfirmDialog
import app.moshu.journal.ui.components.MoShuEmptyState
import app.moshu.journal.ui.components.MoShuPageHeader
import app.moshu.journal.ui.components.MoShuSectionTitle
import app.moshu.journal.ui.components.moodLabel
import app.moshu.journal.ui.theme.AzureBlue
import app.moshu.journal.ui.theme.CategoryColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

@Composable
fun InsightsScreen(
    viewModel: InsightsViewModel,
    onOpenEntry: (Long) -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val writingStats by viewModel.writingStats.collectAsStateWithLifecycle()
    val moodTags by viewModel.moodTags.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    var confirmClearChat by remember { mutableStateOf(false) }
    // 消息项之前固定有 4 个卡片/标题，外加加载态与空状态两项可能占位。
    val headerItems = 4 + (if (state.loading) 1 else 0) + (if (!state.loading && state.entryCount == 0) 1 else 0) + (if (writingStats?.totalEntries == 0) 0 else 1)
    // 只在消息数增长时滚到最后一条；原先监听 asking 会在回答还没生成时就跳走。
    LaunchedEffect(state.messages.size) {
        val size = state.messages.size
        if (size > 0) {
            val target = headerItems + size - 1
            if (target >= 0) listState.animateScrollToItem(target)
        }
    }
    Column(modifier.fillMaxSize()) {
        MoShuPageHeader("回顾", "从记录里，看见自己的轨迹", onSettings = onSettings)
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (state.loading) {
                item {
                    Box(Modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    }
                }
            }
            if (!state.loading && state.entryCount == 0) {
                // 新用户看到的是全 0 的图表，必须说明这是「还没有数据」而不是统计坏了。
                item {
                    MoShuEmptyState(
                        "这个月还没有记录",
                        "写下几条之后，这里会出现心情趋势、活跃日历和月度叙事。",
                    )
                }
            }
            item {
                Card(shape = MaterialTheme.shapes.extraLarge, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = viewModel::prevMonth) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "上个月") }
                            Text(state.monthLabel, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                            IconButton(onClick = viewModel::nextMonth, enabled = state.monthOffset > 0) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "下个月") }
                        }
                        // 能往回翻 24 个月，翻远之后一格一格点回来太费劲。
                        if (state.monthOffset > 0) {
                            TextButton(onClick = viewModel::currentMonth, modifier = Modifier.heightIn(min = 48.dp)) {
                                Text("回到本月")
                            }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                            Stat("记录", state.entryCount.toString())
                            Stat("活跃", "${state.activeDays}天")
                            Stat("连续", "${state.streak}天")
                            Stat("心情", state.avgMood?.let { "%.1f".format(Locale.CHINA, it) } ?: "—")
                        }
                        if (state.activityCounts.isNotEmpty()) {
                            MonthHeatmap(state.activityCounts, state.firstDayOffset)
                        }
                        MoodChart(state.points)
                    }
                }
            }
            item {
                WritingStatsCard(writingStats)
            }
            if (moodTags.isNotEmpty()) {
                item { MoodTagCard(moodTags) }
            }
            item {
                Card(shape = MaterialTheme.shapes.large, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        MoShuSectionTitle("记忆分布")
                        val max = state.categoryCounts.maxOrNull()?.coerceAtLeast(1) ?: 1
                        Category.NAMES.forEachIndexed { index, label ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                // 固定尺寸会在系统字体放大后裁掉「生活/工作/灵感」，改成只约束最小宽度。
                                Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.widthIn(min = 42.dp).wrapContentHeight())
                                Box(Modifier.weight(1f).height(8.dp).background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)) {
                                    Box(Modifier.fillMaxWidth(state.categoryCounts[index].toFloat() / max).height(8.dp).background(CategoryColors[index], CircleShape))
                                }
                                Text(state.categoryCounts[index].toString(), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 8.dp))
                            }
                        }
                    }
                }
            }
            item { ReviewCard(state, viewModel, onSettings) }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        MoShuSectionTitle("问墨枢", modifier = Modifier.weight(1f))
                        // 对话是持久化在本地的，必须给一个彻底的删除入口。
                        if (state.messages.isNotEmpty()) {
                            TextButton(onClick = { confirmClearChat = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                                Text("清空对话", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                    // 对话是全局的，切换月份不会换一份记录，必须写清楚，否则用户会以为聊的是当前月。
                    Text(
                        "对话不受月份切换影响；提问和相关摘录会发送给你配置的 AI 服务商。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (state.messages.isEmpty()) {
                item { ChatEmptyState(state.aiConfigured, onSettings) }
            } else {
                items(state.messages, key = { it.id }) { message ->
                    ChatBubble(
                        message = message,
                        onOpenEntry = onOpenEntry,
                        onRegenerate = { viewModel.regenerate(message.id) },
                        onRetry = { viewModel.retry(message.id) },
                    )
                }
            }
            if (state.asking) item { AskingBubble(onStop = viewModel::cancelAsk) }
        }
        if (state.askHint.isNotBlank()) {
            Text(
                state.askHint,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.secondary,
            )
        }
        if (state.suggestions.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                state.suggestions.forEach { suggestion -> Suggestion(suggestion) { viewModel.ask(it) } }
            }
        }
        AskBar(viewModel::ask)
    }

    if (confirmClearChat) {
        MoShuConfirmDialog(
            title = "清空全部对话？",
            body = "本机保存的问答记录会被删除，此操作无法撤销。",
            confirmLabel = "清空",
            destructive = true,
            onConfirm = viewModel::clearChat,
            onDismiss = { confirmClearChat = false },
        )
    }
}

@Composable private fun Stat(label: String, value: String) { Column(horizontalAlignment = Alignment.CenterHorizontally) { Text(value, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.secondary); Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }

/**
 * 写作统计卡。全库口径，不随所看月份变化。
 *
 * 这些数字回答的是「我到底写了多少」——比月度图表更能支撑长期记录的动力。
 */
@Composable
private fun WritingStatsCard(stats: WritingStats?) {
    if (stats == null || stats.totalEntries == 0) return
    Card(shape = MaterialTheme.shapes.large, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            MoShuSectionTitle("写作统计")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                Stat("累计", "${stats.totalEntries}条")
                Stat("字数", formatCount(stats.totalChars))
                Stat("均长", "${stats.averageChars}字")
                Stat("最长连续", "${stats.longestStreak}天")
            }
            stats.busiestHour?.let { hour ->
                Text(
                    "最常写字的时间：${hour.toString().padStart(2, '0')}:00 前后",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 万字以上换算成「x.x万」，否则直接给数字。 */
private fun formatCount(value: Int): String =
    if (value >= 10_000) "%.1f万".format(Locale.CHINA, value / 10_000.0) else "$value"

/**
 * 情绪与标签的关联卡。
 *
 * 只展示记录数够多的标签（见仓库层阈值）：两三条记录得出的「关联」是噪音。
 */
@Composable
private fun MoodTagCard(links: List<MoodTagLink>) {
    Card(shape = MaterialTheme.shapes.large, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            MoShuSectionTitle("情绪与标签")
            Text(
                "某个标签反复伴随同一种情绪时，这里会显示出来。样本太少的不列。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            links.forEach { link ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("#${link.tag}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text(
                        "${moodLabel(link.mood)} · ${(link.ratio * 100).toInt()}%",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                    Text(
                        "  (${link.moodCount}/${link.total})",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ReviewCard(state: InsightsUiState, viewModel: InsightsViewModel, onSettings: () -> Unit) {
    val review = state.review
    Card(shape = MaterialTheme.shapes.large, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.size(8.dp))
                Text("月度叙事", style = MaterialTheme.typography.titleMedium)
                // 出处放在标题旁边：跟在按钮后面容易被长正文挤出可视区，
                // 而且「谁在什么时候、依据多少条记录生成的」本来就该紧跟标题。
                review?.let { saved ->
                    Spacer(Modifier.weight(1f))
                    Text(
                        reviewStamp(saved),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.End,
                    )
                }
            }
            // 用户可以在本地改一句话，而不必为了一个错字整篇重生成；改动不落库。
            var editing by rememberSaveable(review?.periodKey) { mutableStateOf(false) }
            var edited by rememberSaveable(review?.periodKey) { mutableStateOf<String?>(null) }
            val shown = edited ?: review?.content ?: "把这个月散落的片段，整理成一段看得懂的生活主线。"
            val clipboard = LocalClipboardManager.current
            if (editing) {
                OutlinedTextField(
                    value = shown,
                    onValueChange = { edited = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                    label = { Text("修改回顾") },
                )
            } else {
                SelectionContainer { Text(shown, style = MaterialTheme.typography.bodyMedium) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = viewModel::generateMonthlyReview, enabled = state.entryCount > 0 && !state.generatingReview) {
                    if (state.generatingReview) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Text(if (review == null) "生成本月回顾" else "重新生成")
                }
                if (review != null || edited != null) {
                    IconButton(onClick = { clipboard.setText(AnnotatedString(shown)) }) { Icon(Icons.Rounded.ContentCopy, "复制回顾") }
                    TextButton(onClick = {
                        // 之前改动只留在内存里，切个月份就丢；现在落回同一条回顾记录。
                        if (editing && edited != null) viewModel.saveReview(shown)
                        editing = !editing
                    }) { Text(if (editing) "保存修改" else "编辑") }
                }
            }
            if (state.reviewModelChanged) {
                Text("（模型已变更，可重新生成）", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            state.reviewNotice?.let { notice ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        notice.text,
                        style = MaterialTheme.typography.labelMedium,
                        color = noticeColor(notice.severity),
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    when (notice.action) {
                        ReviewAction.SETTINGS -> TextButton(onClick = onSettings) { Text("去配置") }
                        ReviewAction.REGENERATE -> TextButton(onClick = viewModel::generateMonthlyReview) { Text("重新生成") }
                        null -> Unit
                    }
                }
            }
        }
    }
}

@Composable
private fun noticeColor(severity: ReviewSeverity) = when (severity) {
    ReviewSeverity.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
    ReviewSeverity.WARN -> MaterialTheme.colorScheme.tertiary
    ReviewSeverity.ERROR -> MaterialTheme.colorScheme.error
}

private fun reviewStamp(review: AiReviewEntity): String = buildList {
    if (review.entryCount > 0) add("基于 ${review.entryCount} 条记录")
    if (review.model.isNotBlank()) add(review.model)
    add(SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).format(Date(review.generatedAt)))
}.joinToString(" · ")

@Composable
private fun ChatEmptyState(configured: Boolean, onSettings: () -> Unit) {
    if (!configured) {
        // 没配置时只给一句提示，用户点建议只会得到一句「未配置」然后自己去找设置——这里直接给入口。
        Card(shape = MaterialTheme.shapes.large, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("先配置 AI 服务", style = MaterialTheme.typography.titleMedium)
                Text(
                    "配置后就能就自己的记录提问。提问内容和命中的摘录会发送给你填写的服务商，答案只依据本地记忆。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = onSettings) { Text("去配置 AI 服务") }
            }
        }
    } else {
        MoShuEmptyState(
            "还没有开始对话",
            "就自己的记录问点什么，答案只依据本地记忆，并会标注引用了哪几条。",
        )
    }
}

@Composable
private fun Suggestion(text: String, onClick: (String) -> Unit) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface, modifier = Modifier.clickable { onClick(text) }) {
        Text(text, Modifier.padding(horizontal = 12.dp, vertical = 9.dp), style = MaterialTheme.typography.labelMedium)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatBubble(
    message: ChatTurn,
    onOpenEntry: (Long) -> Unit,
    onRegenerate: () -> Unit,
    onRetry: () -> Unit,
) {
    val user = message.role == "user"
    var menu by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (user) Arrangement.End else Arrangement.Start) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = if (user) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
            contentColor = if (user) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .fillMaxWidth(0.88f)
                .wrapContentWidth(if (user) Alignment.End else Alignment.Start)
                // 回答没有重试/复制入口时，一个错答只能让用户重打问题。
                .combinedClickable(onClick = { if (!user) menu = true }, onLongClick = { if (!user) menu = true }),
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(message.text, style = MaterialTheme.typography.bodyMedium)
                if (user && message.status == TurnStatus.PENDING) {
                    Text("等待回答…", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.75f))
                }
                if (user && message.status == TurnStatus.INTERRUPTED) {
                    Text(
                        "已中断，点按重试",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier
                            .clickable(onClickLabel = "重试这个问题") { onRetry() }
                            .heightIn(min = 40.dp)
                            .wrapContentHeight(Alignment.CenterVertically),
                    )
                }
                if (!user && message.isAnswer) {
                    if (message.sourceEntryIds.isEmpty()) {
                        Text("未引用具体记录", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            message.sourceEntryIds.take(3).forEachIndexed { index, id ->
                                Text(
                                    "来源 ${index + 1}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.secondary,
                                    modifier = Modifier
                                        .clickable(onClickLabel = "打开来源记忆") { onOpenEntry(id) }
                                        .heightIn(min = 48.dp)
                                        .wrapContentHeight(Alignment.CenterVertically)
                                        .padding(horizontal = 4.dp),
                                )
                            }
                            if (message.sourceEntryIds.size > 3) Text("+${message.sourceEntryIds.size - 3}", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                if (!user) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        Box {
                            IconButton(onClick = { menu = true }, modifier = Modifier.size(36.dp)) {
                                Icon(Icons.Rounded.MoreVert, "更多操作", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text("重新生成") }, onClick = { menu = false; onRegenerate() })
                                DropdownMenuItem(text = { Text("复制") }, onClick = { menu = false; clipboard.setText(AnnotatedString(message.text)) })
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 回答等待中的占位气泡：显示已等待秒数，让慢模型和卡死可区分，并允许取消。 */
@Composable
private fun AskingBubble(onStop: () -> Unit) {
    var seconds by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            seconds += 1
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.wrapContentWidth(Alignment.Start),
        ) {
            Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                Spacer(Modifier.size(8.dp))
                Text("正在翻阅你的记忆… ${seconds}s", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                TextButton(onClick = onStop) { Text("停止") }
            }
        }
    }
}

@Composable
private fun AskBar(onSend: (String) -> Boolean) {
    var text by remember { mutableStateOf("") }
    Surface(shadowElevation = 8.dp, color = MaterialTheme.colorScheme.surface) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(text, { text = it }, Modifier.weight(1f), placeholder = { Text("问点关于自己的事…") }, maxLines = 3, shape = MaterialTheme.shapes.large)
            // 只有被受理才清空输入：忙时拒发要保留用户的文字，避免打了一半的问题被吞掉。
            IconButton(onClick = { if (onSend(text)) text = "" }, enabled = text.isNotBlank()) { Icon(Icons.AutoMirrored.Rounded.Send, "发送") }
        }
    }
}

@Composable
private fun MonthHeatmap(counts: List<Int>, firstDayOffset: Int) {
    val active = MaterialTheme.colorScheme.primary
    val idle = MaterialTheme.colorScheme.surfaceVariant
    val maxCount = counts.maxOrNull()?.coerceAtLeast(1) ?: 1
    val summary = "本月活跃热力图：${counts.count { it > 0 }} 天有记录，最多一天 ${counts.maxOrNull() ?: 0} 条"
    Column(
        verticalArrangement = Arrangement.spacedBy(5.dp),
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = summary },
    ) {
        Row(Modifier.fillMaxWidth()) {
            listOf("一", "二", "三", "四", "五", "六", "日").forEach { day ->
                Text(day, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            }
        }
        val cells = counts.size + firstDayOffset
        val weeks = (cells + 6) / 7
        for (week in 0 until weeks) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (weekday in 0 until 7) {
                    val index = week * 7 + weekday - firstDayOffset
                    if (index < 0 || index >= counts.size) {
                        Spacer(Modifier.weight(1f).height(16.dp))
                    } else {
                        val ratio = counts[index].toFloat() / maxCount
                        Box(
                            Modifier.weight(1f).height(16.dp).background(
                                if (counts[index] == 0) idle else active.copy(alpha = 0.35f + 0.65f * ratio),
                                RoundedCornerShape(4.dp),
                            ),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MoodChart(points: List<MoodPoint>) {
    val line = AzureBlue
    val grid = MaterialTheme.colorScheme.outlineVariant
    val scored = points.mapNotNull { it.score }
    val summary = if (scored.isEmpty()) {
        "本月还没有可统计的心情记录"
    } else {
        "本月心情走势：共 ${scored.size} 天有记录，平均 ${"%.1f".format(Locale.CHINA, scored.average())}"
    }
    Canvas(Modifier.fillMaxWidth().height(150.dp).padding(vertical = 10.dp).semantics { contentDescription = summary }) {
        if (points.isEmpty()) return@Canvas
        val width = size.width
        val height = size.height - 14.dp.toPx()
        repeat(5) { index ->
            val y = height * index / 4f
            drawLine(grid, Offset(0f, y), Offset(width, y), 1.dp.toPx())
        }
        fun x(day: Int) = width * (day - 1f) / (points.size - 1).coerceAtLeast(1)
        fun y(score: Double) = height * (1f - ((score - 1) / 4).toFloat())
        val scored = points.filter { it.score != null }
        if (scored.size > 1) {
            val path = Path()
            scored.forEachIndexed { index, point -> if (index == 0) path.moveTo(x(point.day), y(point.score!!)) else path.lineTo(x(point.day), y(point.score!!)) }
            drawPath(path, line, style = Stroke(3.dp.toPx()))
        }
        scored.forEach { drawCircle(line, 4.dp.toPx(), Offset(x(it.day), y(it.score!!))) }
    }
}