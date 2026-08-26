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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.moshu.journal.data.db.Category
import app.moshu.journal.ui.components.EntryCard
import app.moshu.journal.ui.components.MoShuEmptyState
import app.moshu.journal.ui.components.MoShuPageHeader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun MemoryScreen(
    viewModel: RecordViewModel,
    onOpenEntry: (Long) -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Column(modifier.fillMaxSize()) {
        if (state.pendingCount > 0) LinearProgressIndicator(Modifier.fillMaxWidth())
        MoShuPageHeader(
            title = "记忆",
            subtitle = "${state.entries.size} 条正在被记住",
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
            item { FilterChip(state.filters.mood == "great", { viewModel.setMood(if (state.filters.mood == "great") null else "great") }, { Text("✨") }) }
            item { FilterChip(state.filters.mood == "good", { viewModel.setMood(if (state.filters.mood == "good") null else "good") }, { Text("🙂") }) }
            item { FilterChip(state.filters.mood == "low", { viewModel.setMood(if (state.filters.mood == "low") null else "low") }, { Text("😕") }) }
        }

        if (state.entries.isEmpty()) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                MoShuEmptyState(
                    if (state.filters.query.isBlank()) "还没有这类记忆" else "没有找到相关记忆",
                    if (state.filters.query.isBlank()) "换个筛选看看，或回到今天写下一笔。" else "试试更短的关键词或标签。",
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val formatter = SimpleDateFormat("M月d日 EEEE", Locale.CHINA)
                val grouped = state.entries.groupBy { formatter.format(Date(it.createdAt)) }
                grouped.forEach { (day, entries) ->
                    item(key = "day-$day") {
                        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(day, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.weight(1f))
                            Text("${entries.size} 条", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    items(entries, key = { it.id }) { entry ->
                        EntryCard(entry = entry, onClick = { onOpenEntry(entry.id) }, attachments = state.attachments[entry.id].orEmpty())
                    }
                }
            }
        }
    }
}
