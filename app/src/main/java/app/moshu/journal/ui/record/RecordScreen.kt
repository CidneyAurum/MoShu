package app.moshu.journal.ui.record

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.moshu.journal.data.db.Category
import app.moshu.journal.ui.components.EntryCard
import app.moshu.journal.ui.components.EntryCardActions
import app.moshu.journal.ui.components.MoShuEmptyState
import app.moshu.journal.ui.components.MoShuPageHeader
import app.moshu.journal.ui.components.shareEntry
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun MemoryScreen(
    viewModel: RecordViewModel,
    onOpenEntry: (Long) -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val filtering = state.filters.active

    Column(modifier.fillMaxSize()) {
        if (state.pendingCount > 0) LinearProgressIndicator(Modifier.fillMaxWidth())
        MoShuPageHeader(
            title = "记忆",
            // 搜索/筛选时显示命中数，否则用户看不出筛选是否生效。
            subtitle = if (filtering) "找到 ${state.entries.size} 条" else "${state.entries.size} 条正在被记住",
            onSettings = onSettings,
            actions = {
                IconButton(onClick = viewModel::toggleSearch) {
                    Icon(if (state.filters.searchMode) Icons.Rounded.Close else Icons.Rounded.Search, contentDescription = "搜索")
                }
            },
        )
        AnimatedVisibility(state.filters.searchMode) {
            OutlinedTextField(
                value = state.filters.query,
                onValueChange = viewModel::setQuery,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                placeholder = { Text("搜索内容、标签或概括") },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                trailingIcon = {
                    if (state.filters.query.isNotBlank()) {
                        IconButton(onClick = { viewModel.setQuery("") }) {
                            Icon(Icons.Rounded.Close, contentDescription = "清空搜索")
                        }
                    }
                },
                singleLine = true,
                shape = MaterialTheme.shapes.large,
            )
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { FilterChip(state.filters.category == null, { viewModel.setCategory(null) }, { Text("全部") }) }
            items(Category.NAMES.size) { index ->
                FilterChip(state.filters.category == index, { viewModel.setCategory(index) }, { Text(Category.NAMES[index]) })
            }
            item {
                FilterChip(
                    selected = state.filters.pinnedOnly,
                    onClick = viewModel::togglePinnedOnly,
                    label = { Text("置顶") },
                    leadingIcon = { Icon(Icons.Rounded.PushPin, null) },
                )
            }
            // 五种情绪都要能筛：原先只有三个纯 emoji 的块，neutral/bad 的条目永远筛不出来，
            // 而且 TalkBack 只会读出一个孤零零的「✨」。
            items(MOOD_FILTERS) { (value, label) ->
                FilterChip(
                    selected = state.filters.mood == value,
                    onClick = { viewModel.setMood(if (state.filters.mood == value) null else value) },
                    label = { Text(label) },
                )
            }
            item {
                FilterChip(
                    selected = state.filters.failedOnly,
                    onClick = { viewModel.setFailedOnly(!state.filters.failedOnly) },
                    label = { Text("整理失败") },
                    leadingIcon = { Icon(Icons.Rounded.ErrorOutline, null) },
                )
            }
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items(EntrySort.entries.toList()) { sort ->
                FilterChip(
                    selected = state.filters.sort == sort,
                    onClick = { viewModel.setSort(sort) },
                    label = { Text(sort.label) },
                )
            }
            if (filtering) {
                item {
                    TextButton(onClick = viewModel::clearFilters, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("清除筛选", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }

        if (state.loading) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            }
        } else if (state.entries.isEmpty()) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                MoShuEmptyState(
                    when {
                        state.filters.failedOnly -> "没有整理失败的记忆"
                        state.filters.query.isNotBlank() -> "没有找到相关记忆"
                        else -> "还没有这类记忆"
                    },
                    when {
                        state.filters.failedOnly -> "所有记录都整理好了，或换个筛选看看。"
                        state.filters.query.isNotBlank() -> "试试更短的关键词或标签。"
                        else -> "换个筛选看看，或回到今天写下一笔。"
                    },
                    icon = if (state.filters.query.isNotBlank()) Icons.Rounded.SearchOff else Icons.Rounded.EditNote,
                )
            }
        } else {
            // 分组与格式化器必须放在 LazyColumn 内容 lambda 之外：
            // 那个 lambda 每次重组（含每一帧滚动）都会重新执行。
            val dayFormatter = remember { DateTimeFormatter.ofPattern("M月d日 EEEE", Locale.CHINA) }
            val grouped = remember(state.entries) {
                state.entries.groupBy { Instant.ofEpochMilli(it.createdAt).atZone(ZoneId.systemDefault()).toLocalDate() }
            }
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                grouped.forEach { (day, dayEntries) ->
                    item(key = "day-$day") {
                        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(day.format(dayFormatter), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.weight(1f))
                            Text("${dayEntries.size} 条", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    items(dayEntries, key = { it.id }) { entry ->
                        EntryCard(
                            entry = entry,
                            onClick = { onOpenEntry(entry.id) },
                            attachments = state.attachments[entry.id].orEmpty(),
                            actions = EntryCardActions(
                                onEdit = { onOpenEntry(entry.id) },
                                onDuplicate = { viewModel.duplicate(entry) },
                                onTogglePin = { viewModel.togglePinned(entry) },
                                onShare = { shareEntry(context, entry) },
                                onDelete = { viewModel.delete(entry) },
                            ),
                        )
                    }
                }
            }
        }
    }
}

/** 情绪筛选块：值与文案必须与详情页编辑器的选项一致，覆盖全部五种情绪。 */
private val MOOD_FILTERS = listOf(
    "great" to "✨ 很好",
    "good" to "🙂 不错",
    "neutral" to "😌 平静",
    "low" to "😕 低落",
    "bad" to "😞 难过",
)

private val EntrySort.label: String
    get() = when (this) {
        EntrySort.NEWEST -> "最新"
        EntrySort.OLDEST -> "最早"
        EntrySort.UPDATED -> "最近修改"
    }
