package app.moshu.journal.ui.insights

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.moshu.journal.ai.AskEngine.ChatMessage
import app.moshu.journal.data.db.Category
import app.moshu.journal.ui.components.MoShuEmptyState
import app.moshu.journal.ui.components.MoShuPageHeader
import app.moshu.journal.ui.components.MoShuSectionTitle
import app.moshu.journal.ui.theme.AzureBlue
import app.moshu.journal.ui.theme.CategoryColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun InsightsScreen(
    viewModel: InsightsViewModel,
    onOpenEntry: (Long) -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    // 新答案会追加在长列表底部，主动滚下去，否则用户看不到刚生成的回答。
    LaunchedEffect(state.messages.size, state.asking) {
        if (state.messages.isNotEmpty()) listState.animateScrollToItem(listState.layoutInfo.totalItemsCount)
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
                Card(shape = MaterialTheme.shapes.large, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        MoShuSectionTitle("记忆分布")
                        val max = state.categoryCounts.maxOrNull()?.coerceAtLeast(1) ?: 1
                        Category.NAMES.forEachIndexed { index, label ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.size(width = 42.dp, height = 20.dp))
                                Box(Modifier.weight(1f).height(8.dp).background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)) {
                                    Box(Modifier.fillMaxWidth(state.categoryCounts[index].toFloat() / max).height(8.dp).background(CategoryColors[index], CircleShape))
                                }
                                Text(state.categoryCounts[index].toString(), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 8.dp))
                            }
                        }
                    }
                }
            }
            item {
                Card(shape = MaterialTheme.shapes.large, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.size(8.dp))
                            Text("月度叙事", style = MaterialTheme.typography.titleMedium)
                        }
                        Text(state.review?.content ?: "把这个月散落的片段，整理成一段看得懂的生活主线。", style = MaterialTheme.typography.bodyMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Button(onClick = viewModel::generateMonthlyReview, enabled = state.entryCount > 0 && !state.generatingReview) {
                                if (state.generatingReview) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                else Text(if (state.review == null) "生成本月回顾" else "重新生成")
                            }
                            state.review?.let { review ->
                                Text(
                                    SimpleDateFormat("M月d日更新", Locale.CHINA).format(Date(review.generatedAt)),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        if (state.reviewMessage.isNotBlank()) {
                            Text(
                                state.reviewMessage,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    MoShuSectionTitle("问墨枢")
                    Text("答案只依据你的本地记忆，引用来源可以直接打开。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (state.messages.isEmpty()) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Suggestion("最近在忙什么？", viewModel::ask)
                        Suggestion("这个月心情怎样？", viewModel::ask)
                    }
                }
            } else {
                items(state.messages, key = { it.id }) { message -> ChatBubble(message, onOpenEntry) }
            }
            if (state.asking) item { Text("正在翻阅你的记忆…", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
        }
        AskBar(!state.asking, viewModel::ask)
    }
}

@Composable private fun Stat(label: String, value: String) { Column(horizontalAlignment = Alignment.CenterHorizontally) { Text(value, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.secondary); Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }

@Composable
private fun Suggestion(text: String, onClick: (String) -> Unit) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface, modifier = Modifier.clickable { onClick(text) }) {
        Text(text, Modifier.padding(horizontal = 12.dp, vertical = 9.dp), style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun ChatBubble(message: ChatMessage, onOpenEntry: (Long) -> Unit) {
    val user = message.role == "user"
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (user) Arrangement.End else Arrangement.Start) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = if (user) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
            contentColor = if (user) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.fillMaxWidth(0.88f).wrapContentWidth(if (user) Alignment.End else Alignment.Start),
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(message.text, style = MaterialTheme.typography.bodyMedium)
                if (!user && message.sourceEntryIds.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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
                        if (message.sourceCount > 3) Text("+${message.sourceCount - 3}", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun AskBar(enabled: Boolean, onSend: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    Surface(shadowElevation = 8.dp, color = MaterialTheme.colorScheme.surface) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(text, { text = it }, Modifier.weight(1f), placeholder = { Text("问点关于自己的事…") }, maxLines = 3, enabled = enabled, shape = MaterialTheme.shapes.large)
            IconButton(onClick = { onSend(text); text = "" }, enabled = enabled && text.isNotBlank()) { Icon(Icons.AutoMirrored.Rounded.Send, "发送") }
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
