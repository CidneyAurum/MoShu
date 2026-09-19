package app.moshu.journal.ui.today

import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Refresh
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.moshu.journal.data.media.ImageStorage
import app.moshu.journal.ui.components.EntryCard
import app.moshu.journal.ui.components.MoShuEmptyState
import app.moshu.journal.ui.components.MoShuPageHeader
import app.moshu.journal.ui.components.MoShuSectionTitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun TodayScreen(
    viewModel: TodayViewModel,
    onOpenEntry: (Long) -> Unit,
    onOpenMemory: () -> Unit,
    onOpenActions: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val today = LocalDate.now()
    var promptOffset by remember(today) { mutableStateOf(0) }
    val prompt = prompts[(today.dayOfYear + promptOffset) % prompts.size]

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item {
            MoShuPageHeader(
                title = "今天",
                subtitle = today.format(DateTimeFormatter.ofPattern("M月d日 EEEE", Locale.CHINA)),
                onSettings = onSettings,
            )
        }
        item {
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(greeting(), style = MaterialTheme.typography.headlineMedium)
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Lightbulb, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.size(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("此刻可以记", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                            Text(prompt, style = MaterialTheme.typography.bodyLarge)
                        }
                        IconButton(onClick = { promptOffset++ }) {
                            Icon(Icons.Rounded.Refresh, contentDescription = "换一条提示", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(19.dp))
                        }
                    }
                }
                CaptureComposer(state.saving, viewModel::add)
                TodayStats(
                    entryCount = state.entries.size,
                    pendingCount = state.pendingCount,
                    todoCount = state.activeTodos.size,
                    onMemory = onOpenMemory,
                    onActions = onOpenActions,
                )
            }
        }
        item {
            Row(Modifier.padding(horizontal = 20.dp)) {
                MoShuSectionTitle("今天的记忆", action = if (state.entries.isNotEmpty()) "查看全部" else null, onAction = onOpenMemory)
            }
        }
        if (state.entries.isEmpty()) {
            item { MoShuEmptyState("今天还很轻", "记下一句话或一张图，让今天留下轮廓。", icon = Icons.Rounded.EditNote) }
        } else {
            items(state.entries.take(4), key = { it.id }) { entry ->
                EntryCard(
                    entry = entry,
                    onClick = { onOpenEntry(entry.id) },
                    modifier = Modifier.padding(horizontal = 20.dp),
                    attachments = state.attachments[entry.id].orEmpty(),
                )
            }
        }
    }
}

@Composable
private fun CaptureComposer(saving: Boolean, onAdd: (String, List<Uri>, () -> Unit) -> Unit) {
    var draft by remember { mutableStateOf("") }
    val images = remember { mutableStateListOf<Uri>() }
    val haptic = LocalHapticFeedback.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(ImageStorage.MAX_ATTACHMENTS)) { selected ->
        images.clear()
        images.addAll(selected.take(ImageStorage.MAX_ATTACHMENTS))
    }

    fun submit() {
        if (!saving && (draft.isNotBlank() || images.isNotEmpty())) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            onAdd(draft, images.toList()) {
                draft = ""
                images.clear()
            }
        }
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.extraLarge,
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("想到什么，就落下一笔…") },
                minLines = 3,
                maxLines = 7,
                shape = RoundedCornerShape(18.dp),
                supportingText = {
                    if (draft.length > 400) Text("${draft.length} 字", style = MaterialTheme.typography.labelSmall)
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { submit() }),
            )
            AnimatedVisibility(images.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    images.take(4).forEach { uri ->
                        Box(Modifier.size(62.dp).clip(RoundedCornerShape(12.dp))) {
                            UriImage(uri, Modifier.fillMaxSize())
                            Icon(
                                Icons.Rounded.Close,
                                contentDescription = "移除图片",
                                modifier = Modifier.align(Alignment.TopEnd).size(22.dp).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f), CircleShape).clickable { images.remove(uri) }.padding(3.dp),
                            )
                        }
                    }
                    if (images.size > 4) {
                        Box(Modifier.size(62.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                            Text("+${images.size - 4}", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                    if (images.size < ImageStorage.MAX_ATTACHMENTS) {
                        Box(
                            Modifier.size(62.dp).clip(RoundedCornerShape(12.dp)).clickable {
                                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            }.background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Rounded.Add, contentDescription = "继续添加图片", tint = MaterialTheme.colorScheme.secondary)
                        }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = {
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }) {
                    Icon(Icons.Rounded.Image, contentDescription = "添加图片", tint = MaterialTheme.colorScheme.secondary)
                }
                Text("图片仅存本机", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                IconButton(
                    onClick = { submit() },
                    enabled = !saving && (draft.isNotBlank() || images.isNotEmpty()),
                    modifier = Modifier.size(48.dp).background(
                        if (!saving && (draft.isNotBlank() || images.isNotEmpty())) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                        CircleShape,
                    ),
                ) {
                    if (saving) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = "保存记录")
                }
            }
        }
    }
}

@Composable
private fun TodayStats(entryCount: Int, pendingCount: Int, todoCount: Int, onMemory: () -> Unit, onActions: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        StatCard("记录", entryCount.toString(), Modifier.weight(1f).clip(MaterialTheme.shapes.medium).clickable(onClick = onMemory))
        StatCard("整理中", pendingCount.toString(), Modifier.weight(1f))
        StatCard("待行动", todoCount.toString(), Modifier.weight(1f).clip(MaterialTheme.shapes.medium).clickable(onClick = onActions))
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(14.dp)) {
            Text(value, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.secondary)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun UriImage(uri: Uri, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var bitmap by remember(uri) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    LaunchedEffect(uri) {
        bitmap = withContext(Dispatchers.IO) {
            runCatching {
                val decoded = if (Build.VERSION.SDK_INT >= 28) decodeModern(context, uri) else @Suppress("DEPRECATION") MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
                decoded.asImageBitmap()
            }.getOrNull()
        }
    }
    if (bitmap != null) Image(bitmap!!, null, modifier, contentScale = ContentScale.Crop)
    else Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant))
}

@androidx.annotation.RequiresApi(28)
private fun decodeModern(context: android.content.Context, uri: Uri): android.graphics.Bitmap =
    ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri))

private fun greeting(): String = when (java.time.LocalTime.now().hour) {
    in 5..10 -> "早上好，慢慢开始。"
    in 11..13 -> "中午好，记下此刻。"
    in 14..18 -> "下午好，今天进行得怎样？"
    else -> "晚上好，给今天留个底。"
}

private val prompts = listOf(
    "今天哪一刻最想留住？",
    "脑海里反复出现的事是什么？",
    "有什么微小的进展值得记下？",
    "此刻的心情更像哪种天气？",
    "今天答应了自己什么？",
    "最近冒出了什么新念头？",
    "有什么事，写下来会轻一点？",
)
