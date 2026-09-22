package app.moshu.journal.ui.today

import android.graphics.BitmapFactory
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material3.FilterChip
import app.moshu.journal.ui.components.ENTRY_TEMPLATES
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.moshu.journal.MoShuApp
import app.moshu.journal.data.media.ImageStorage
import app.moshu.journal.ui.components.EntryCard
import app.moshu.journal.ui.components.EntryCardActions
import app.moshu.journal.ui.components.MoShuEmptyState
import app.moshu.journal.ui.components.MoShuPageHeader
import app.moshu.journal.ui.components.MoShuSectionTitle
import app.moshu.journal.ui.components.shareEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.text.style.TextOverflow
import app.moshu.journal.data.db.EntryEntity
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
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
    /** 列表里点击 AI 状态标签时的重试入口；未接入时用本页作用域直接提交整理。 */
    onRetryAi: ((Long) -> Unit)? = null,
    /** 从外部分享进来的草稿：预填到输入框，让用户先改再存。 */
    initialDraft: String = "",
    onDraftConsumed: () -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val today = LocalDate.now()
    var promptOffset by remember(today) { mutableIntStateOf(0) }
    val prompt = prompts[(today.dayOfYear + promptOffset) % prompts.size]
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // 未配置 AI 时不该展示「整理中」这类卡片：它在宣传一个关掉的功能，还像卡住的任务。
    val aiConfigured by remember { MoShuApp.instance.settings.aiConfig.map { it.valid } }
        .collectAsStateWithLifecycle(false)
    val retryAi: (Long) -> Unit = onRetryAi ?: { id -> scope.launch { MoShuApp.instance.journal.reEnrich(id) } }

    // 整理完成是一次静默的写库，必须给一次可见反馈，否则用户不知道发生了什么。
    LaunchedEffect(state.justEnrichedId) {
        val id = state.justEnrichedId ?: return@LaunchedEffect
        viewModel.consumeEnriched()
        MoShuApp.instance.notices.post("AI 整理完成", "查看") { onOpenEntry(id) }
    }

    // 应用长时间驻留后台后跨过午夜时，需要把「今天」的范围推到新的一天。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshDay()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 一直停在本页跨过 00:00 时 ON_RESUME 不会触发，标题日期与列表会停在昨天。
    // 等到下一个整点再比对一次日期，跨天就刷新并换一条提示语。
    LaunchedEffect(today) {
        while (true) {
            val now = LocalDateTime.now()
            val nextMidnight = now.toLocalDate().plusDays(1).atStartOfDay()
            delay(Duration.between(now, nextMidnight).toMillis().coerceAtLeast(1_000L))
            promptOffset = 0
            viewModel.refreshDay()
        }
    }

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
                CaptureComposer(
                    saving = state.saving,
                    draft = state.draft,
                    onDraftChange = viewModel::onDraftChange,
                    onAdd = viewModel::add,
                    initialDraft = initialDraft,
                    onDraftConsumed = onDraftConsumed,
                )
                // 模板放在输入框下方：先看到输入框，需要结构时再往下拿。
                // 只在还没写内容时出现，避免误触覆盖已经写好的正文。
                if (state.draft.isBlank()) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item {
                            Text(
                                "模板",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 10.dp),
                            )
                        }
                        items(ENTRY_TEMPLATES) { template ->
                            FilterChip(
                                selected = false,
                                onClick = { viewModel.onDraftChange(template.body) },
                                label = { Text(template.label) },
                            )
                        }
                    }
                }
                if (state.message.isNotBlank()) {
                    Text(state.message, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                }
                TodayStats(
                    entryCount = state.entries.size,
                    pendingCount = state.pendingCount,
                    todoCount = state.activeTodos.size,
                    online = state.online,
                    aiConfigured = aiConfigured,
                    onMemory = onOpenMemory,
                    onActions = onOpenActions,
                )
            }
        }
        // 往年今日放在「今天的记忆」之前：这是回看类应用最容易被记住的功能，
        // 藏在列表下面等于没有。没有往年记录时整块不出现，不占位置。
        if (state.memories.isNotEmpty()) {
            item {
                Row(Modifier.padding(horizontal = 20.dp)) {
                    MoShuSectionTitle("往年今日", action = "共 ${state.memories.size} 条")
                }
            }
            items(state.memories.take(3), key = { "memory-${it.id}" }) { entry ->
                MemoryCard(entry = entry, onClick = { onOpenEntry(entry.id) }, modifier = Modifier.padding(horizontal = 20.dp))
            }
        }
        item {
            Row(Modifier.padding(horizontal = 20.dp)) {
                MoShuSectionTitle("今天的记忆", action = if (state.entries.isNotEmpty()) "查看全部" else null, onAction = onOpenMemory)
            }
        }
        if (state.loading) {
            item {
                Box(Modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                }
            }
        } else if (state.entries.isEmpty()) {
            item { MoShuEmptyState("今天还很轻", "记下一句话或一张图，让今天留下轮廓。", icon = Icons.Rounded.EditNote) }
        } else {
            items(state.entries.take(4), key = { it.id }) { entry ->
                EntryCard(
                    entry = entry,
                    onClick = { onOpenEntry(entry.id) },
                    modifier = Modifier.padding(horizontal = 20.dp),
                    attachments = state.attachments[entry.id].orEmpty(),
                    onRetryAi = { retryAi(entry.id) },
                    actions = EntryCardActions(
                        onEdit = { onOpenEntry(entry.id) },
                        onDuplicate = { viewModel.duplicate(entry) },
                        onTogglePin = { viewModel.togglePinned(entry) },
                        // 收藏在两处卡片菜单都要能切：只在记忆页有会让「今天」的用户以为没有这个功能。
                        onToggleStar = { viewModel.toggleStarred(entry) },
                        onShare = { shareEntry(context, entry) },
                        onDelete = { viewModel.delete(entry) },
                        onRetryAi = { retryAi(entry.id) },
                    ),
                )
            }
            // 第 5 条起原先直接消失，用户会以为记录没存上。
            if (state.entries.size > 4) {
                item {
                    TextButton(onClick = onOpenMemory, modifier = Modifier.padding(horizontal = 20.dp)) {
                        Text("还有 ${state.entries.size - 4} 条今天的记忆 →")
                    }
                }
            }
        }
    }
}

/**
 * 往年今日卡片。刻意做得比普通条目更淡、更短：它是回看用的线索，
 * 不是今天要处理的内容，抢视觉焦点反而干扰记录。
 */
@Composable
private fun MemoryCard(entry: EntryEntity, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val yearsAgo = remember(entry.createdAt) {
        val then = Instant.ofEpochMilli(entry.createdAt).atZone(ZoneId.systemDefault()).toLocalDate()
        (LocalDate.now().year - then.year).coerceAtLeast(1)
    }
    val dayFormat = remember { DateTimeFormatter.ofPattern("M月d日", Locale.CHINA) }
    Card(
        modifier = modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "$yearsAgo 年前的今天",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    Instant.ofEpochMilli(entry.createdAt).atZone(ZoneId.systemDefault()).toLocalDate().format(dayFormat),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                entry.summary.ifBlank { entry.content }.replace('\n', ' ').take(90),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun CaptureComposer(    saving: Boolean,
    draft: String,
    onDraftChange: (String) -> Unit,
    onAdd: (String, List<Uri>, () -> Unit) -> Unit,
    initialDraft: String = "",
    onDraftConsumed: () -> Unit = {},
) {
    // 分享进来的文字直接填进输入框，而不是静默入库——用户应当有机会先修改。
    LaunchedEffect(initialDraft) {
        if (initialDraft.isNotBlank()) {
            onDraftChange(initialDraft)
            onDraftConsumed()
        }
    }
    val images = remember { mutableStateListOf<Uri>() }
    val haptic = LocalHapticFeedback.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(ImageStorage.MAX_ATTACHMENTS)) { selected ->
        images.clear()
        images.addAll(selected.take(ImageStorage.MAX_ATTACHMENTS))
    }

    fun submit() {
        if (!saving && (draft.isNotBlank() || images.isNotEmpty())) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            // 草稿由 ViewModel 在保存成功后清空，这里只负责清掉本地预览的图片。
            onAdd(draft, images.toList()) { images.clear() }
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
                onValueChange = onDraftChange,
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
                            // 触控区补到 48dp，避免 22dp 的「移除」既难点中又容易误触。
                            Box(
                                modifier = Modifier.align(Alignment.TopEnd).size(48.dp)
                                    .clickable(onClickLabel = "移除这张图片") { images.remove(uri) },
                                contentAlignment = Alignment.TopEnd,
                            ) {
                                Box(
                                    modifier = Modifier.size(24.dp).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f), CircleShape),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(Icons.Rounded.Close, "移除图片", Modifier.size(16.dp))
                                }
                            }
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
private fun TodayStats(entryCount: Int, pendingCount: Int, todoCount: Int, online: Boolean, aiConfigured: Boolean, onMemory: () -> Unit, onActions: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        StatCard("记录", entryCount.toString(), Modifier.weight(1f).clip(MaterialTheme.shapes.medium).clickable(onClick = onMemory))
        if (aiConfigured) {
            when {
                // 离线时 AI 整理任务被网络约束挡住不会执行，这里如实说明，避免看起来像卡死。
                pendingCount > 0 -> StatCard(if (!online) "等待网络" else "整理中", pendingCount.toString(), Modifier.weight(1f))
                else -> StatCard("AI 整理", "已完成", Modifier.weight(1f))
            }
        }
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
                // 预览图只有 62dp，必须下采样：全尺寸解码一张相机原图就是几十 MB，
                // 同时选 4 张足以把内存打满。
                val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
                val sample = sampleSizeFor(bounds.outWidth, bounds.outHeight, THUMBNAIL_TARGET_PX)
                val options = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
                val decoded = context.contentResolver.openInputStream(uri)?.use { stream ->
                    android.graphics.BitmapFactory.decodeStream(stream, null, options)
                }
                decoded?.asImageBitmap()
            }.getOrNull()
        }
    }
    val current = bitmap
    if (current != null) Image(current, null, modifier, contentScale = ContentScale.Crop)
    else Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant))
}

private const val THUMBNAIL_TARGET_PX = 256

/** 返回使宽高都不小于 target 的最小 2 的幂采样率。 */
private fun sampleSizeFor(width: Int, height: Int, target: Int): Int {
    if (width <= 0 || height <= 0) return 1
    var sample = 1
    while (width / (sample * 2) >= target && height / (sample * 2) >= target) sample *= 2
    return sample
}

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
