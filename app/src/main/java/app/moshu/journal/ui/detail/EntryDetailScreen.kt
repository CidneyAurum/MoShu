package app.moshu.journal.ui.detail

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.moshu.journal.MoShuApp
import app.moshu.journal.ai.AiConfig
import app.moshu.journal.data.db.AttachmentEntity
import app.moshu.journal.data.db.Category
import app.moshu.journal.data.db.EntryAiState
import app.moshu.journal.data.media.ImageStorage
import app.moshu.journal.ui.components.AiActions
import app.moshu.journal.ui.components.EntryImageStrip
import app.moshu.journal.ui.components.LocalImage
import app.moshu.journal.ui.components.MoShuConfirmDialog
import app.moshu.journal.ui.components.MoShuPageHeader
import app.moshu.journal.ui.components.moodLabel
import app.moshu.journal.ui.components.parseTags
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.StarOutline
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.text.style.TextOverflow
import app.moshu.journal.ai.Hosts
import app.moshu.journal.ui.components.MessageSeverity
import app.moshu.journal.ui.components.MoShuMessageBar
import app.moshu.journal.ui.components.rememberCopyText
import app.moshu.journal.ui.components.THUMBNAIL_TARGET_PX
import app.moshu.journal.ui.components.shareEntry
import app.moshu.journal.ui.theme.OnScrim

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EntryDetailScreen(
    viewModel: EntryDetailViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val entry = state.entry
    // 只读已保存的 AI 配置，用于说明「这条正文被发给了谁」。
    val aiConfig by remember { MoShuApp.instance.settings.aiConfig }.collectAsStateWithLifecycle(AiConfig())
    val context = LocalContext.current
    val aiTodos = state.todos.filter { it.isAiSuggested }
    val linkedTodos = state.todos.filter { !it.isAiSuggested }
    var editing by remember { mutableStateOf(false) }
    var editorDirty by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var selectedImage by remember { mutableStateOf<AttachmentEntity?>(null) }
    var todoDraft by remember { mutableStateOf("") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(ImageStorage.MAX_ATTACHMENTS)) {
        viewModel.addImages(it)
    }

    if (entry == null) {
        Column(modifier.fillMaxSize()) {
            MoShuPageHeader(title = "记忆详情", onBack = onBack)
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                // 首次查询还没回来时不能断言「已删除」——那只是初始状态。
                if (state.loading) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                } else {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(horizontal = 32.dp),
                    ) {
                        Text("这条记忆已经不存在", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "它可能已在其他页面被删除。返回后可以继续浏览其它记忆。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
        return
    }

    Column(modifier.fillMaxSize()) {
        MoShuPageHeader(
            title = if (editing) "编辑记忆" else "记忆详情",
            // 改过之后要能看出这条不是原样，否则用户无法判断 AI 是否已重写。
            subtitle = buildString {
                append(SimpleDateFormat("yyyy年M月d日 HH:mm", Locale.CHINA).format(Date(entry.createdAt)))
                if (entry.updatedAt > entry.createdAt) {
                    append(" · 修改于 ")
                    append(SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).format(Date(entry.updatedAt)))
                }
            },
            // 编辑态直接返回会静默丢内容，先问一次。
            onBack = { if (editing && editorDirty) confirmDiscard = true else onBack() },
            actions = {
                if (!editing) {
                    IconButton(onClick = { shareEntry(context, entry) }) { Icon(Icons.Rounded.Share, "分享") }
                    IconButton(onClick = viewModel::togglePinned) {
                        Icon(Icons.Rounded.PushPin, "置顶", tint = if (entry.isPinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { editing = true }) { Icon(Icons.Rounded.Edit, "编辑") }
                }
            },
        )
        if (editing) {
            EntryEditor(
                entry = entry,
                attachments = state.attachments,
                busy = state.busy,
                message = state.message,
                onDirtyChange = { editorDirty = it },
                onCancel = { if (editorDirty) confirmDiscard = true else editing = false },
                onSave = { content, category, tags, mood, summary ->
                    viewModel.save(content, category, tags, mood, summary) {
                        editorDirty = false
                        editing = false
                    }
                },
                onAddImages = {
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
                onDeleteImage = viewModel::deleteImage,
                onSetCover = viewModel::setCover,
                onRestoreAi = viewModel::clearManualMetadata,
            )
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (state.attachments.isNotEmpty()) {
                    item { EntryImageStrip(state.attachments.take(3), targetPx = DETAIL_IMAGE_PX, onClick = { selectedImage = it }) }
                }
                if (entry.summary.isNotBlank()) item { Text(entry.summary, style = MaterialTheme.typography.headlineMedium) }
                if (entry.content.isNotBlank()) {
                    item {
                        // 长文折叠：整段正文作为单个 Text 会让首屏与滚动都很重。
                        var expanded by remember(entry.id) { mutableStateOf(false) }
                        val long = entry.content.length > LONG_TEXT_CHARS
                        val clipboard = rememberCopyText()
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            // 正文可选中复制：以前只有回顾页能选中，详情页只能整条分享。
                            SelectionContainer {
                                Text(
                                    entry.content,
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = if (expanded || !long) Int.MAX_VALUE else COLLAPSED_LINES,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (long) {
                                    TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起" else "展开全文") }
                                }
                                TextButton(onClick = { clipboard(entry.content) }) { Text("复制全文") }
                            }
                        }
                    }
                }
                item {
                    // 标签是用户/AI 生成的，长度不可控，普通 Row 会静默裁掉尾部。
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        InfoPill(Category.nameOf(entry.categoryId))
                        entry.mood.takeIf { it.isNotBlank() }?.let { InfoPill(moodLabel(it)) }
                        parseTags(entry.tagsJson).take(4).forEach { InfoPill("#$it") }
                    }
                }
                if (entry.aiState == EntryAiState.SUCCEEDED.value) {
                    // 整理成功此前完全不可见，用户无法确认概括/标签是否已更新。
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.AutoAwesome, null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.secondary)
                            Spacer(Modifier.size(6.dp))
                            Text(
                                "已由 ${entry.aiModel.ifBlank { aiConfig.model.ifBlank { "AI" } }} 整理",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = viewModel::retryAi) { Text(AiActions.RETRY_LABEL) }
                        }
                    }
                }
                if (entry.aiState != EntryAiState.SUCCEEDED.value) {
                    item {
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(AiActions.title(entry.aiState), style = MaterialTheme.typography.titleMedium)
                                Text(AiActions.hint(entry.aiState, entry.aiError), style = MaterialTheme.typography.bodySmall)
                                // 说清楚这条正文到底有没有离开设备、发给了谁。
                                if (entry.aiState != EntryAiState.IDLE.value) {
                                    Text(
                                        "正文已发送至 ${providerHost(aiConfig.baseUrl)} 整理 · ${entry.aiModel.ifBlank { aiConfig.model }}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                if (AiActions.canRetry(entry.aiState)) {
                                    OutlinedButton(onClick = viewModel::retryAi) {
                                        Icon(Icons.Rounded.Refresh, null)
                                        Spacer(Modifier.size(6.dp))
                                        Text(AiActions.RETRY_LABEL)
                                    }
                                }
                            }
                        }
                    }
                }
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, null, tint = MaterialTheme.colorScheme.secondary)
                                Spacer(Modifier.size(8.dp))
                                Text("关联行动", style = MaterialTheme.typography.titleMedium)
                                Spacer(Modifier.weight(1f))
                                if (linkedTodos.isNotEmpty()) {
                                    Text("${linkedTodos.count { !it.done }}/${linkedTodos.size} 进行中", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            linkedTodos.forEach { todo ->
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                    Checkbox(checked = todo.done, onCheckedChange = { viewModel.toggleTodo(todo) })
                                    Text(
                                        todo.text,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = if (todo.done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                                        textDecoration = if (todo.done) TextDecoration.LineThrough else null,
                                        modifier = Modifier.weight(1f),
                                    )
                                    todo.dueEpochDay?.let { due ->
                                        Text(
                                            LocalDate.ofEpochDay(due.toLong()).format(DateTimeFormatter.ofPattern("M月d日")),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (!todo.done && due < LocalDate.now().toEpochDay().toInt()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    // 删除关联行动不必跑到「行动」页；删除可撤销。
                                    IconButton(onClick = { viewModel.deleteTodo(todo) }) {
                                        Icon(Icons.Rounded.DeleteOutline, "删除行动", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                                    }
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(
                                    value = todoDraft,
                                    onValueChange = { todoDraft = it },
                                    modifier = Modifier.weight(1f),
                                    placeholder = { Text("把这条记忆变成下一步…") },
                                    singleLine = true,
                                    shape = MaterialTheme.shapes.medium,
                                )
                                IconButton(
                                    onClick = { viewModel.addTodo(todoDraft); todoDraft = "" },
                                    enabled = todoDraft.isNotBlank(),
                                ) {
                                    Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, "添加行动", tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                }
                if (aiTodos.isNotEmpty()) {
                    item {
                        // AI 提取的行动先在这里等用户表态，不再无声无息地混进「行动」。
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Rounded.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary)
                                    Spacer(Modifier.size(8.dp))
                                    Text("AI 建议的行动", style = MaterialTheme.typography.titleMedium)
                                }
                                Text(
                                    "这些行动由 AI 从正文中提取，采纳后才会进入「行动」。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                aiTodos.forEach { todo ->
                                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                        Text(todo.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                        TextButton(onClick = { viewModel.acceptAiTodo(todo) }) { Text("采纳") }
                                        TextButton(onClick = { viewModel.dismissAiTodo(todo) }) {
                                            Text("忽略", color = MaterialTheme.colorScheme.error)
                                        }
                                    }
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
                if (state.message.isNotBlank()) {
                    item { MoShuMessageBar(state.message, severity = if (state.messageError) MessageSeverity.ERROR else MessageSeverity.INFO) }
                }
            }
        }
    }

    selectedImage?.let { image ->
        val index = state.attachments.indexOfFirst { it.id == image.id }
        // 多图条目要能左右滑动翻页；只给一个「3 / 5」文字等于让用户退出去再点下一张。
        val pagerState = rememberPagerState(initialPage = index.coerceAtLeast(0)) { state.attachments.size }
        Dialog(onDismissRequest = { selectedImage = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim), contentAlignment = Alignment.Center) {
                HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                    val attachment = state.attachments.getOrNull(page) ?: return@HorizontalPager
                    LocalImage(
                        attachment.localPath,
                        Modifier.fillMaxSize(),
                        ContentScale.Fit,
                        contentDescription = "第 ${page + 1} 张，共 ${state.attachments.size} 张",
                        targetPx = FULL_IMAGE_PX,
                    )
                }
                if (state.attachments.size > 1) {
                    Text(
                        "${pagerState.currentPage + 1} / ${state.attachments.size}",
                        color = OnScrim,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.align(Alignment.TopStart).padding(24.dp).background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.5f), CircleShape).padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
                IconButton(onClick = { selectedImage = null }, modifier = Modifier.align(Alignment.TopEnd).padding(20.dp).background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.5f), CircleShape)) {
                    Icon(Icons.Rounded.Close, "关闭", tint = OnScrim)
                }
            }
        }
    }

    if (confirmDiscard) {
        MoShuConfirmDialog(
            title = "放弃这次修改？",
            body = "你改动的内容还没有保存，离开后会丢失。",
            confirmLabel = "放弃修改",
            destructive = true,
            onConfirm = {
                editorDirty = false
                editing = false
            },
            onDismiss = { confirmDiscard = false },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除这条记忆？") },
            text = { Text("正文、图片和由它产生的未编辑待办都会一起删除；删除后可以立即撤销。") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; viewModel.delete(onBack) }) { Text("删除", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
private fun EntryEditor(
    entry: app.moshu.journal.data.db.EntryEntity,
    attachments: List<AttachmentEntity>,
    busy: Boolean,
    message: String,
    onDirtyChange: (Boolean) -> Unit,
    onCancel: () -> Unit,
    onSave: (String, Int, List<String>, String, String) -> Unit,
    onAddImages: () -> Unit,
    onDeleteImage: (Long) -> Unit,
    onSetCover: (Long) -> Unit,
    onRestoreAi: (Int) -> Unit,
) {
    var content by remember(entry.id) { mutableStateOf(entry.content) }
    var summary by remember(entry.id) { mutableStateOf(entry.summary) }
    var tags by remember(entry.id) { mutableStateOf(parseTags(entry.tagsJson).joinToString("，")) }
    var category by remember(entry.id) { mutableIntStateOf(entry.categoryId) }
    var mood by remember(entry.id) { mutableStateOf(entry.mood) }
    var pendingImageDelete by remember(entry.id) { mutableStateOf<Long?>(null) }

    // 与初值比对判断是否有未保存改动，供返回/取消时拦截。
    val dirty = content != entry.content ||
        summary != entry.summary ||
        category != entry.categoryId ||
        mood != entry.mood ||
        tags != parseTags(entry.tagsJson).joinToString("，")
    LaunchedEffect(dirty) { onDirtyChange(dirty) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { OutlinedTextField(content, { content = it }, Modifier.fillMaxWidth(), label = { Text("正文") }, minLines = 6, shape = MaterialTheme.shapes.large) }
        item { OutlinedTextField(summary, { summary = it.take(60) }, Modifier.fillMaxWidth(), label = { Text("一句话概括") }, shape = MaterialTheme.shapes.medium) }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Category.NAMES.forEachIndexed { index, label -> FilterChip(category == index, { category = index }, { Text(label) }) }
            }
        }
        item {
            // 五个带文字的 FilterChip 在 360dp 宽的机器上放不下一行，末位会被裁掉且点不到。
            FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("great" to "✨ 很好", "good" to "🙂 不错", "neutral" to "😌 平静", "low" to "😕 低落", "bad" to "😞 难过").forEach { (value, label) ->
                    FilterChip(mood == value, { mood = value }, { Text(label) })
                }
            }
        }
        item { OutlinedTextField(tags, { tags = it }, Modifier.fillMaxWidth(), label = { Text("标签，用逗号分隔") }, shape = MaterialTheme.shapes.medium) }
        if (attachments.isNotEmpty()) {
            item {
                // 用可横向滚动的列表而不是 Row(take(4))：上限是 9 张，
                // 取前 4 张会让剩下的图片在编辑态里既看不到也删不掉。
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(attachments, key = { it.id }) { image ->
                        var menu by remember(image.id) { mutableStateOf(false) }
                        Box(Modifier.size(68.dp).clip(RoundedCornerShape(12.dp))) {
                            LocalImage(image.localPath, Modifier.fillMaxSize(), targetPx = THUMBNAIL_TARGET_PX)
                            // 触控区至少 48dp：原来只有 22dp，既难点中又容易误触，
                            // 而且删图片会立刻删磁盘文件，所以补一层确认。
                            Box(
                                modifier = Modifier.align(Alignment.TopEnd).size(48.dp)
                                    .clickable(onClickLabel = "删除这张图片") { pendingImageDelete = image.id },
                                contentAlignment = Alignment.TopEnd,
                            ) {
                                Box(
                                    modifier = Modifier.size(24.dp).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f), CircleShape),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(Icons.Rounded.Close, "删除图片", Modifier.size(16.dp))
                                }
                            }
                            // 长按图片可以设封面：sortOrder 决定列表缩略图与详情首图。
                            Box(
                                modifier = Modifier.matchParentSize()
                                    .combinedClickable(onClick = {}, onLongClickLabel = "图片操作", onLongClick = { menu = true }),
                                contentAlignment = Alignment.BottomStart,
                            ) {
                                if (attachments.firstOrNull()?.id == image.id) {
                                    Text(
                                        "封面",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = OnScrim,
                                        modifier = Modifier.padding(4.dp).background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
                                    )
                                }
                            }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(
                                    text = { Text("设为封面") },
                                    leadingIcon = { Icon(Icons.Rounded.StarOutline, null) },
                                    onClick = { menu = false; onSetCover(image.id) },
                                )
                                DropdownMenuItem(
                                    text = { Text("删除这张图片", color = MaterialTheme.colorScheme.error) },
                                    leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) },
                                    onClick = { menu = false; pendingImageDelete = image.id },
                                )
                            }
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
        if (entry.manualMetadataMask != 0) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "概括、标签、分类或情绪中有你手动改过的项，AI 不会再覆盖它们。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = { onRestoreAi(entry.manualMetadataMask) }) { Text("恢复 AI 管理") }
                }
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
        if (message.isNotBlank()) {
            item { Text(message, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }

    pendingImageDelete?.let { imageId ->
        AlertDialog(
            onDismissRequest = { pendingImageDelete = null },
            title = { Text("删除这张图片？") },
            text = { Text("图片会从本机移除，无法恢复。") },
            confirmButton = {
                Button(onClick = { onDeleteImage(imageId); pendingImageDelete = null }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { pendingImageDelete = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun InfoPill(text: String) {
    Text(text, modifier = Modifier.background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(9.dp)).padding(horizontal = 9.dp, vertical = 5.dp), style = MaterialTheme.typography.labelMedium)
}

private fun splitTags(raw: String): List<String> = raw.split(',', '，', '#').map { it.trim() }.filter { it.isNotBlank() }.distinct().take(6)

/** 展示用服务商主机名；没写协议的地址也尽量解析。 */
private fun providerHost(url: String): String = Hosts.of(url).ifBlank { "你配置的服务商" }

/** 长文折叠阈值与折叠行数。 */
private const val LONG_TEXT_CHARS = 300
private const val COLLAPSED_LINES = 12

/** 列表缩略图 320px 足够；详情条带与全屏大图才需要更高分辨率。 */
private const val DETAIL_IMAGE_PX = 720
private const val FULL_IMAGE_PX = 1600
