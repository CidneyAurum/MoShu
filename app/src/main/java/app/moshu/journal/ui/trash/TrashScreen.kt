package app.moshu.journal.ui.trash

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.RestoreFromTrash
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import app.moshu.journal.data.db.EntryEntity
import app.moshu.journal.ui.components.MoShuConfirmDialog
import app.moshu.journal.ui.components.MoShuEmptyState
import app.moshu.journal.ui.components.MoShuPageHeader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 回收站。
 *
 * 删除的记忆先在这里放 [retentionDays] 天，期间随时可以恢复；过期后启动时自动清理。
 * 界面上明确写出保留期，用户才知道「现在不处理会怎样」。
 */
@Composable
fun TrashScreen(
    viewModel: TrashViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var purging by remember { mutableStateOf<EntryEntity?>(null) }
    var confirmEmpty by remember { mutableStateOf(false) }
    val dayFormat = remember { SimpleDateFormat("M月d日 HH:mm", Locale.CHINA) }

    Column(modifier.fillMaxSize()) {
        MoShuPageHeader(
            title = "回收站",
            subtitle = if (state.entries.isEmpty()) null else "${state.entries.size} 条 · 保留 ${viewModel.retentionDays} 天后自动清理",
            onBack = onBack,
            actions = {
                if (state.entries.isNotEmpty()) {
                    TextButton(onClick = { confirmEmpty = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("清空", color = MaterialTheme.colorScheme.error)
                    }
                }
            },
        )
        when {
            state.loading -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            }
            state.entries.isEmpty() -> Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                MoShuEmptyState(
                    "回收站是空的",
                    "删除的记忆会先放到这里，${viewModel.retentionDays} 天内都可以恢复。",
                    icon = Icons.Rounded.DeleteOutline,
                )
            }
            else -> LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.entries, key = { it.id }) { entry ->
                    TrashRow(
                        entry = entry,
                        deletedLabel = dayFormat.format(Date(entry.deletedAt)),
                        onRestore = { viewModel.restore(entry) },
                        onPurge = { purging = entry },
                    )
                }
            }
        }
    }

    purging?.let { entry ->
        MoShuConfirmDialog(
            title = "彻底删除？",
            body = "这条记忆和它的图片会被永久删除，无法恢复。",
            confirmLabel = "永久删除",
            destructive = true,
            onConfirm = { viewModel.purge(entry); purging = null },
            onDismiss = { purging = null },
        )
    }

    if (confirmEmpty) {
        MoShuConfirmDialog(
            title = "清空回收站？",
            body = "${state.entries.size} 条记忆及其图片会被永久删除，无法恢复。",
            confirmLabel = "全部永久删除",
            destructive = true,
            onConfirm = { viewModel.purgeAll(); confirmEmpty = false },
            onDismiss = { confirmEmpty = false },
        )
    }
}

@Composable
private fun TrashRow(entry: EntryEntity, deletedLabel: String, onRestore: () -> Unit, onPurge: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    entry.content.replace('\n', ' ').take(60).ifBlank { "（无正文）" },
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "删除于 $deletedLabel",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onRestore, modifier = Modifier.heightIn(min = 48.dp)) { Text("恢复") }
            IconButton(onClick = onPurge) {
                Icon(Icons.Rounded.DeleteForever, "彻底删除", tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}
