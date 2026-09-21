package app.moshu.journal.ui.tags

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.MergeType
import androidx.compose.material.icons.rounded.Sell
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.moshu.journal.ui.components.MessageSeverity
import app.moshu.journal.ui.components.MoShuConfirmDialog
import app.moshu.journal.ui.components.MoShuEmptyState
import app.moshu.journal.ui.components.MoShuMessageBar
import app.moshu.journal.ui.components.MoShuPageHeader

/**
 * 标签管理页：重命名 / 合并 / 删除。
 *
 * 三种操作都只改 `tagsJson`，不会删除任何记录——这一点在确认文案里明确写出来，
 * 否则用户会担心「删标签」等于「删内容」。
 */
@Composable
fun TagsScreen(
    viewModel: TagsViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var renaming by remember { mutableStateOf<String?>(null) }
    var merging by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<String?>(null) }

    Column(modifier.fillMaxSize()) {
        MoShuPageHeader(
            title = "标签",
            subtitle = if (state.tags.isEmpty()) null else "共 ${state.tags.size} 个标签",
            onBack = onBack,
        )
        if (state.message.isNotBlank()) {
            MoShuMessageBar(
                state.message,
                Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                if (state.error) MessageSeverity.WARN else MessageSeverity.INFO,
            )
        }
        when {
            state.loading -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            }
            state.tags.isEmpty() -> Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                MoShuEmptyState(
                    "还没有标签",
                    "标签由 AI 整理时自动补全，也可以在详情页手动添加。之后就能在这里改名与合并。",
                    icon = Icons.Rounded.Sell,
                )
            }
            else -> LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.tags, key = { it.first }) { (tag, count) ->
                    TagRow(
                        tag = tag,
                        count = count,
                        onRename = { renaming = tag },
                        onMerge = { merging = tag },
                        onDelete = { deleting = tag },
                    )
                }
            }
        }
    }

    renaming?.let { tag ->
        TagTextDialog(
            title = "重命名标签",
            label = "新名称",
            initial = tag,
            confirmLabel = "重命名",
            onConfirm = { viewModel.rename(tag, it); renaming = null },
            onDismiss = { renaming = null },
        )
    }

    merging?.let { tag ->
        val others = state.tags.map { it.first }.filter { it != tag }
        TagPickerDialog(
            tag = tag,
            candidates = others,
            onPick = { viewModel.merge(tag, it); merging = null },
            onDismiss = { merging = null },
        )
    }

    deleting?.let { tag ->
        MoShuConfirmDialog(
            title = "删除标签「$tag」？",
            body = "只会把「$tag」从记录上摘掉，记录本身不会被删除。",
            confirmLabel = "移除标签",
            destructive = true,
            onConfirm = { viewModel.delete(tag); deleting = null },
            onDismiss = { deleting = null },
        )
    }
}

@Composable
private fun TagRow(tag: String, count: Int, onRename: () -> Unit, onMerge: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                Text(
                    "#$tag",
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.secondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                "$count 条",
                modifier = Modifier.weight(1f).padding(start = 10.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Rounded.Edit, "管理标签 $tag", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("重命名") }, leadingIcon = { Icon(Icons.Rounded.Edit, null) }, onClick = { menu = false; onRename() })
                    DropdownMenuItem(text = { Text("合并到…") }, leadingIcon = { Icon(Icons.Rounded.MergeType, null) }, onClick = { menu = false; onMerge() })
                    DropdownMenuItem(
                        text = { Text("移除标签", color = MaterialTheme.colorScheme.error) },
                        leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) },
                        onClick = { menu = false; onDelete() },
                    )
                }
            }
        }
    }
}

@Composable
private fun TagTextDialog(
    title: String,
    label: String,
    initial: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text(label) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value) }, enabled = value.isNotBlank()) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun TagPickerDialog(tag: String, candidates: List<String>, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("把「$tag」合并到") },
        text = {
            if (candidates.isEmpty()) {
                Text("还没有其它标签可以合并。可以直接用「重命名」改成一个新名字。")
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    items(candidates) { candidate ->
                        TextButton(
                            onClick = { onPick(candidate) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        ) { Text("#$candidate", modifier = Modifier.fillMaxWidth()) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
