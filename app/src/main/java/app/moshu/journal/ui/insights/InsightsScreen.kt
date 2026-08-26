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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.moshu.journal.ai.AskEngine.ChatMessage
import app.moshu.journal.data.db.Category
import app.moshu.journal.ui.components.MoShuPageHeader
import app.moshu.journal.ui.components.MoShuSectionTitle
import app.moshu.journal.ui.theme.AzureBlue
import app.moshu.journal.ui.theme.CategoryColors

@Composable
fun InsightsScreen(
    viewModel: InsightsViewModel,
    onOpenEntry: (Long) -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    Column(modifier.fillMaxSize()) {
        MoShuPageHeader("回顾", "从记录里，看见自己的轨迹", onSettings = onSettings)
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
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
                            Stat("心情", state.avgMood?.let { "%.1f".format(it) } ?: "—")
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
                        Button(onClick = viewModel::generateMonthlyReview, enabled = state.entryCount > 0 && !state.generatingReview) {
                            if (state.generatingReview) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            else Text(if (state.review == null) "生成本月回顾" else "重新生成")
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
                items(state.messages, key = { "${it.role}-${it.text}-${it.sourceCount}" }) { message -> ChatBubble(message, onOpenEntry) }
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
            modifier = Modifier.fillMaxWidth(0.88f),
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(message.text, style = MaterialTheme.typography.bodyMedium)
                if (!user && message.sourceEntryIds.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        message.sourceEntryIds.take(3).forEachIndexed { index, id ->
                            Text("来源 ${index + 1}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary, modifier = Modifier.clickable { onOpenEntry(id) }.padding(vertical = 4.dp))
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
private fun MoodChart(points: List<MoodPoint>) {
    val line = AzureBlue
    val grid = MaterialTheme.colorScheme.outlineVariant
    Canvas(Modifier.fillMaxWidth().height(150.dp).padding(vertical = 10.dp)) {
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
