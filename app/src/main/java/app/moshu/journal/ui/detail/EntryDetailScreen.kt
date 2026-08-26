package app.moshu.journal.ui.detail

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.moshu.journal.data.db.AttachmentEntity
import app.moshu.journal.data.db.Category
import app.moshu.journal.data.db.EntryAiState
import app.moshu.journal.data.media.ImageStorage
import app.moshu.journal.ui.components.EntryImageStrip
import app.moshu.journal.ui.components.LocalImage
import app.moshu.journal.ui.components.MoShuPageHeader
import app.moshu.journal.ui.components.parseTags
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun EntryDetailScreen(
    viewModel: EntryDetailViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val entry = state.entry
    var editing by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var selectedImage by remember { mutableStateOf<AttachmentEntity?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(ImageStorage.MAX_ATTACHMENTS)) {
        viewModel.addImages(it)
    }

    if (entry == null) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("这条记忆已经不存在") }
        return
    }

    Column(modifier.fillMaxSize()) {
        MoShuPageHeader(
            title = if (editing) "编辑记忆" else "记忆详情",
            subtitle = SimpleDateFormat("yyyy年M月d日 HH:mm", Locale.CHINA).format(Date(entry.createdAt)),
            onBack = onBack,
            actions = {
                if (!editing) {
                    IconButton(onClick = viewModel::togglePinned) {
                        Icon(Icons.Rounded.PushPin, "置顶", tint = if (entry.isPinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { editing = true }) { Icon(Icons.Rounded.Edit, "编辑") }
                }
            },
        )
        if (editing) {
            EntryEditor(entry, state.attachments, state.busy, onCancel = { editing = false }, onSave = { content, category, tags, mood, summary ->
                viewModel.save(content, category, tags, mood, summary) { editing = false }
            }, onAddImages = {
                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }, onDeleteImage = viewModel::deleteImage)
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (state.attachments.isNotEmpty()) {
                    item { EntryImageStrip(state.attachments.take(3), onClick = { selectedImage = it }) }
                }
                if (entry.summary.isNotBlank()) item { Text(entry.summary, style = MaterialTheme.typography.headlineMedium) }
                if (entry.content.isNotBlank()) item { Text(entry.content, style = MaterialTheme.typography.bodyLarge) }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        InfoPill(Category.nameOf(entry.categoryId))
                        entry.mood.takeIf { it.isNotBlank() }?.let { InfoPill(moodLabel(it)) }
                        parseTags(entry.tagsJson).take(4).forEach { InfoPill("#$it") }
                    }
                }
                if (entry.aiState != EntryAiState.SUCCEEDED.value) {
                    item {
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(aiTitle(entry.aiState), style = MaterialTheme.typography.titleMedium)
                                Text(entry.aiError.ifBlank { "连接 AI 后，墨枢会补全概括、标签、情绪和行动项。" }, style = MaterialTheme.typography.bodySmall)
                                OutlinedButton(onClick = viewModel::retryAi) {
                                    Icon(Icons.Rounded.Refresh, null)
                                    Spacer(Modifier.size(6.dp))
                                    Text("重新整理")
                                }
                            }
                        }
                    }
                }
                item {
                    OutlinedButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Rounded.DeleteOutline, null, tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.size(8.dp))
                        Text("删除这条记忆", color = MaterialTheme.colorScheme.error)
                    }
                }
                if (state.message.isNotBlank()) item { Text(state.message, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }

    selectedImage?.let { image ->
        Dialog(onDismissRequest = { selectedImage = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                LocalImage(image.localPath, Modifier.fillMaxSize(), ContentScale.Fit)
                IconButton(onClick = { selectedImage = null }, modifier = Modifier.align(Alignment.TopEnd).padding(20.dp).background(Color.Black.copy(alpha = 0.5f), CircleShape)) {
                    Icon(Icons.Rounded.Close, "关闭", tint = Color.White)
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除这条记忆？") },
            text = { Text("正文、图片和由它产生的未编辑待办都会一起删除，无法撤销。") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; viewModel.delete(onBack) }) { Text("删除", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun EntryEditor(
    entry: app.moshu.journal.data.db.EntryEntity,
    attachments: List<AttachmentEntity>,
    busy: Boolean,
    onCancel: () -> Unit,
    onSave: (String, Int, List<String>, String, String) -> Unit,
    onAddImages: () -> Unit,
    onDeleteImage: (Long) -> Unit,
) {
    var content by remember(entry.id) { mutableStateOf(entry.content) }
    var summary by remember(entry.id) { mutableStateOf(entry.summary) }
    var tags by remember(entry.id) { mutableStateOf(parseTags(entry.tagsJson).joinToString("，")) }
    var category by remember(entry.id) { mutableIntStateOf(entry.categoryId) }
    var mood by remember(entry.id) { mutableStateOf(entry.mood) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { OutlinedTextField(content, { content = it }, Modifier.fillMaxWidth(), label = { Text("正文") }, minLines = 6, shape = MaterialTheme.shapes.large) }
        item { OutlinedTextField(summary, { summary = it.take(60) }, Modifier.fillMaxWidth(), label = { Text("一句话概括") }, shape = MaterialTheme.shapes.medium) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Category.NAMES.forEachIndexed { index, label -> FilterChip(category == index, { category = index }, { Text(label) }) }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                listOf("great" to "✨ 很好", "good" to "🙂 不错", "neutral" to "😌 平静", "low" to "😕 低落", "bad" to "😞 难过").forEach { (value, label) ->
                    FilterChip(mood == value, { mood = value }, { Text(label) })
                }
            }
        }
        item { OutlinedTextField(tags, { tags = it }, Modifier.fillMaxWidth(), label = { Text("标签，用逗号分隔") }, shape = MaterialTheme.shapes.medium) }
        if (attachments.isNotEmpty()) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    attachments.take(4).forEach { image ->
                        Box(Modifier.size(68.dp).clip(RoundedCornerShape(12.dp))) {
                            LocalImage(image.localPath, Modifier.fillMaxSize())
                            Icon(Icons.Rounded.Close, "删除图片", Modifier.align(Alignment.TopEnd).size(22.dp).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f), CircleShape).clickable { onDeleteImage(image.id) }.padding(3.dp))
                        }
                    }
                }
            }
        }
        item {
            OutlinedButton(onClick = onAddImages, enabled = attachments.size < ImageStorage.MAX_ATTACHMENTS) {
                Icon(Icons.Rounded.AddPhotoAlternate, null)
                Spacer(Modifier.size(8.dp))
                Text("添加图片")
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("取消") }
                Button(
                    onClick = { onSave(content, category, splitTags(tags), mood, summary) },
                    enabled = !busy && (content.isNotBlank() || attachments.isNotEmpty()),
                    modifier = Modifier.weight(1f),
                ) { Text("保存") }
            }
        }
    }
}

@Composable
private fun InfoPill(text: String) {
    Text(text, modifier = Modifier.background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(9.dp)).padding(horizontal = 9.dp, vertical = 5.dp), style = MaterialTheme.typography.labelMedium)
}

private fun splitTags(raw: String): List<String> = raw.split(',', '，', '#').map { it.trim() }.filter { it.isNotBlank() }.distinct().take(6)
private fun moodLabel(value: String): String = when (value) { "great" -> "✨ 很好"; "good" -> "🙂 不错"; "neutral" -> "😌 平静"; "low" -> "😕 低落"; "bad" -> "😞 难过"; else -> value }
private fun aiTitle(state: String): String = when (EntryAiState.from(state)) { EntryAiState.PENDING -> "等待 AI 整理"; EntryAiState.RUNNING -> "AI 正在整理"; EntryAiState.FAILED -> "这次没有整理成功"; EntryAiState.IDLE -> "尚未启用 AI 整理"; EntryAiState.SUCCEEDED -> "已整理" }
