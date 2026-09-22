package app.moshu.journal.ui.components

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.moshu.journal.data.db.AttachmentEntity
import app.moshu.journal.data.db.Category
import app.moshu.journal.data.db.EntryAiState
import app.moshu.journal.data.db.EntryEntity
import app.moshu.journal.ui.theme.CategoryColors
import app.moshu.journal.ui.theme.OnScrim
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import android.content.ClipData
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboardManager
import app.moshu.journal.ui.LocalMoShuApp

@Composable
fun MoShuPageHeader(
    title: String,
    subtitle: String? = null,
    onSettings: (() -> Unit)? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
            }
            Spacer(Modifier.width(2.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineLarge)
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        actions()
        if (onSettings != null) {
            IconButton(
                onClick = onSettings,
                modifier = Modifier.size(48.dp).background(MaterialTheme.colorScheme.surface, CircleShape),
            ) {
                Icon(Icons.Rounded.Settings, contentDescription = "设置", modifier = Modifier.size(21.dp))
            }
        }
    }
}

@Composable
fun MoShuSectionTitle(title: String, modifier: Modifier = Modifier, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        if (action != null && onAction != null) {
            // 用 TextButton 而不是裸 Text + clickable：后者只有约 36dp，够不到 48dp 的最小触控区。
            TextButton(onClick = onAction, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(action, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

/** 提示条的严重程度，决定配色与图标。 */
enum class MessageSeverity { INFO, WARN, ERROR }

/**
 * 页面内提示条。此前同一类「保存失败」在四个页面各写一遍 Text，
 * 颜色与位置都不同（有的红、有的灰），用户难以判断严重程度。
 */
@Composable
fun MoShuMessageBar(text: String, modifier: Modifier = Modifier, severity: MessageSeverity = MessageSeverity.INFO) {
    if (text.isBlank()) return
    val container = when (severity) {
        MessageSeverity.ERROR -> MaterialTheme.colorScheme.errorContainer
        MessageSeverity.WARN -> MaterialTheme.colorScheme.tertiaryContainer
        MessageSeverity.INFO -> MaterialTheme.colorScheme.surfaceVariant
    }
    val content = when (severity) {
        MessageSeverity.ERROR -> MaterialTheme.colorScheme.onErrorContainer
        MessageSeverity.WARN -> MaterialTheme.colorScheme.onTertiaryContainer
        MessageSeverity.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(modifier = modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = container) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.Top) {
            Icon(
                if (severity == MessageSeverity.ERROR) Icons.Rounded.ErrorOutline else Icons.Rounded.Info,
                contentDescription = null,
                modifier = Modifier.size(17.dp),
                tint = content,
            )
            Spacer(Modifier.width(8.dp))
            Text(text, style = MaterialTheme.typography.bodySmall, color = content)
        }
    }
}

/** 统计小卡：今天页与回顾页共用同一套观感。 */
@Composable
fun MoShuStatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(14.dp)) {
            Text(value, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.secondary)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun MoShuEmptyState(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector = Icons.Rounded.AutoAwesome,
    /**
     * 可选的下一步动作。空状态只说「还没有」是没用的，
     * 用户需要知道现在能做什么，所以关键页面都带一个按钮。
     */
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        // 垂直居中，让各页的空状态位置一致（原先在「行动」页顶对齐、在「记忆」页居中）。
        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
    ) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(58.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
        }
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (actionLabel != null && onAction != null) {
            TextButton(onClick = onAction, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(actionLabel, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun EntryCard(
    entry: EntryEntity,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    attachments: List<AttachmentEntity> = emptyList(),
    /** 整理失败时卡片内的重试入口；不传则只保留「整理失败」文案。 */
    onRetryAi: (() -> Unit)? = null,
    /** 长按菜单的动作集合；为 null 时卡片只响应单击。 */
    actions: EntryCardActions? = null,
) {
    var menu by remember { mutableStateOf(false) }
    val interactive = Modifier.combinedClickable(
        onClick = onClick,
        onLongClick = if (actions != null) ({ menu = true }) else null,
        onLongClickLabel = if (actions != null) "更多操作" else null,
    )
    Box(modifier) {
        Card(
            modifier = Modifier.fillMaxWidth()
                .border(0.6.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.large)
                .then(interactive),
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp, pressedElevation = 0.dp),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).background(CategoryColors.getOrElse(entry.categoryId) { CategoryColors[0] }, CircleShape))
                    Spacer(Modifier.width(7.dp))
                    Text(Category.nameOf(entry.categoryId), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (entry.isPinned) {
                        Spacer(Modifier.width(8.dp))
                        Icon(Icons.Rounded.PushPin, contentDescription = "已置顶", modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        relativeTime(entry.createdAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (entry.summary.isNotBlank()) {
                    Text(entry.summary, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (entry.content.isNotBlank()) {
                    Text(entry.content, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 4, overflow = TextOverflow.Ellipsis)
                }
                if (attachments.isNotEmpty()) EntryImageStrip(attachments.take(3))
                val tags = parseTags(entry.tagsJson)
                val mood = moodEmoji(entry.mood)
                if (tags.isNotEmpty() || mood.isNotEmpty() || entry.aiState != EntryAiState.SUCCEEDED.value) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        tags.take(3).forEach { tag ->
                            Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                                Text("#$tag", modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        AiStateLabel(entry.aiState, onOpen = onClick, onRetry = onRetryAi)
                        if (mood.isNotEmpty()) Text(mood)
                    }
                }
            }
        }
        if (actions != null) {
            EntryCardMenu(expanded = menu, entry = entry, actions = actions, onDismiss = { menu = false })
        }
    }
}

/** 卡片长按菜单的动作。全部由调用方提供，卡片本身不持有任何数据访问逻辑。 */
data class EntryCardActions(
    val onEdit: () -> Unit,
    val onDuplicate: () -> Unit,
    val onTogglePin: () -> Unit,
    /** 收藏。与置顶分开：收藏只是标记，不改变排序。 */
    val onToggleStar: (() -> Unit)? = null,
    val onShare: () -> Unit,
    val onDelete: () -> Unit,
    /** 整理失败时的重试；非失败条目为 null。 */
    val onRetryAi: (() -> Unit)? = null,
)

@Composable
private fun EntryCardMenu(expanded: Boolean, entry: EntryEntity, actions: EntryCardActions, onDismiss: () -> Unit) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(text = { Text("编辑") }, leadingIcon = { Icon(Icons.Rounded.Edit, null) }, onClick = { onDismiss(); actions.onEdit() })
        DropdownMenuItem(text = { Text("复制一条") }, leadingIcon = { Icon(Icons.Rounded.ContentCopy, null) }, onClick = { onDismiss(); actions.onDuplicate() })
        DropdownMenuItem(
            text = { Text(if (entry.isPinned) "取消置顶" else "置顶") },
            leadingIcon = { Icon(Icons.Rounded.PushPin, null) },
            onClick = { onDismiss(); actions.onTogglePin() },
        )
        actions.onToggleStar?.let { star ->
            DropdownMenuItem(
                text = { Text(if (entry.isStarred) "取消收藏" else "收藏") },
                leadingIcon = { Icon(if (entry.isStarred) Icons.Rounded.StarBorder else Icons.Rounded.Star, null) },
                onClick = { onDismiss(); star() },
            )
        }
        actions.onRetryAi?.let { retry ->
            DropdownMenuItem(text = { Text("重新整理") }, leadingIcon = { Icon(Icons.Rounded.Refresh, null) }, onClick = { onDismiss(); retry() })
        }
        DropdownMenuItem(text = { Text("分享") }, leadingIcon = { Icon(Icons.Rounded.Share, null) }, onClick = { onDismiss(); actions.onShare() })
        DropdownMenuItem(
            text = { Text("删除", color = MaterialTheme.colorScheme.error) },
            leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) },
            onClick = { onDismiss(); actions.onDelete() },
        )
    }
}

/** 用系统分享面板把一条记忆发出去。只拼文本，不涉及图片或密钥。 */
fun shareEntryText(entry: EntryEntity): String = buildString {
    if (entry.summary.isNotBlank()) appendLine(entry.summary)
    append(entry.content)
    val tags = parseTags(entry.tagsJson)
    if (tags.isNotEmpty()) appendLine().append(tags.joinToString(" ") { "#$it" })
}

fun shareEntry(context: Context, entry: EntryEntity) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, shareEntryText(entry))
    }
    runCatching { context.startActivity(Intent.createChooser(intent, "分享这条记忆")) }
}

@Composable
fun MoShuConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            TextButton(onClick = { onDismiss(); onConfirm() }) {
                Text(confirmLabel, color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
fun EntryImageStrip(
    attachments: List<AttachmentEntity>,
    modifier: Modifier = Modifier,
    /** 解码目标边长。列表缩略图只需 ~320px，详情页才需要更高分辨率。 */
    targetPx: Int = THUMBNAIL_TARGET_PX,
    onClick: ((AttachmentEntity) -> Unit)? = null,
) {
    val shape = RoundedCornerShape(12.dp)
    val shown = attachments.take(3)
    val extra = attachments.size - shown.size
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = modifier.fillMaxWidth()) {
        shown.forEachIndexed { index, attachment ->
            Box(
                modifier = Modifier.weight(1f).aspectRatio(1.35f).clip(shape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .then(if (onClick != null) Modifier.clickable(onClickLabel = "查看大图") { onClick(attachment) } else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                LocalImage(
                    attachment.localPath,
                    Modifier.matchParentSize(),
                    contentDescription = "记忆图片 ${index + 1}，共 ${attachments.size} 张",
                    targetPx = targetPx,
                )
                // 多于三张时给出提示，否则 6 张的条目看起来和 3 张的一模一样。
                if (extra > 0 && index == shown.lastIndex) {
                    Box(
                        Modifier.matchParentSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.45f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("+$extra", color = OnScrim, style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
        repeat((3 - shown.size).coerceAtLeast(0)) { Spacer(Modifier.weight(1f)) }
    }
}

/**
 * 进程级缩略图缓存。此前缓存的生存期只有一次组合，滚动列表或返回页面都会
 * 重新从磁盘解码同一张图。
 *
 * 上限按字节而不是条数：单张 720px 缩略图可达约 2MB，按 24 条算是近 50MB，
 * 在内存本来就紧张的机器上反而成了压力来源。
 */
private val imageCache = object : LruCache<String, ImageBitmap>(
    (Runtime.getRuntime().maxMemory() / 8).coerceAtMost(24L * 1024 * 1024).toInt(),
) {
    override fun sizeOf(key: String, value: ImageBitmap): Int = value.width * value.height * 4
}

@Composable
fun LocalImage(
    path: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    contentDescription: String? = null,
    /** 解码目标边长。列表缩略图传 320，详情与大图再提高，避免为 62dp 的格子解码 720px。 */
    targetPx: Int = 720,
) {
    var attempt by remember(path) { mutableIntStateOf(0) }
    var bitmap by remember(path, attempt) { mutableStateOf(if (attempt == 0) imageCache.get(path) else null) }
    LaunchedEffect(path, attempt) {
        if (bitmap != null) return@LaunchedEffect
        val loaded = withContext(Dispatchers.IO) {
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
                // 按显示尺寸下采样，避免列表中解码全尺寸原图造成卡顿与内存压力
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= targetPx && bounds.outHeight / (sample * 2) >= targetPx) sample *= 2
                BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
            }.getOrNull()
        }
        if (loaded != null) imageCache.put(path, loaded)
        bitmap = loaded
    }
    val current = bitmap
    if (current != null) {
        Image(bitmap = current, contentDescription = contentDescription, modifier = modifier, contentScale = contentScale)
    } else {
        // 解码失败（文件被系统清理、内存不足）时给一次重试机会，而不是永久占位。
        Box(
            modifier.background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable(onClickLabel = "重新加载图片") { attempt++ },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Image, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun AiStateLabel(state: String, onOpen: () -> Unit, onRetry: (() -> Unit)?) {
    val entryState = EntryAiState.from(state)
    // 已整理成功不需要在卡片上占一行：那是最常见的情况，标出来只会增加噪音。
    if (entryState == EntryAiState.SUCCEEDED) return
    val label = AiActions.shortLabel(state)
    val failed = entryState == EntryAiState.FAILED
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
            color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            // 「整理失败」本身没有原因，点它进详情页看失败原因，比让用户自己找快。
            modifier = if (failed) {
                Modifier
                    .clickable(onClickLabel = "查看失败原因") { onOpen() }
                    .heightIn(min = 40.dp)
                    .wrapContentHeight(Alignment.CenterVertically)
            } else {
                Modifier
            },
        )
        if (failed && onRetry != null) {
            // 容器补到 48dp 最小触控区，图标本身保持 16dp。
            IconButton(onClick = onRetry, modifier = Modifier.size(48.dp)) {
                Icon(
                    Icons.Rounded.Refresh,
                    contentDescription = AiActions.RETRY_LABEL,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

fun parseTags(tagsJson: String): List<String> = runCatching {
    val array = JSONArray(tagsJson)
    (0 until array.length()).map { array.getString(it) }
}.getOrDefault(emptyList())

/** 列表缩略图的解码目标边长。 */
const val THUMBNAIL_TARGET_PX = 320

/**
 * 复制文本到系统剪贴板并给出反馈。
 * 走 `setClip(ClipEntry)` 而不是已废弃的 `setText(AnnotatedString)`。
 */
@Composable
fun rememberCopyText(): (String) -> Unit {
    val clipboard = LocalClipboardManager.current
    val app = LocalMoShuApp.current
    return { text ->
        runCatching {
            clipboard.setClip(ClipEntry(ClipData.newPlainText("墨枢", text)))
            app.notices.post("已复制")
        }
        Unit
    }
}

private fun relativeTime(timestamp: Long): String {
    val delta = (System.currentTimeMillis() - timestamp).coerceAtLeast(0)
    val minute = 60_000L
    val hour = 60 * minute
    val day = 24 * hour
    return when {
        delta < minute -> "刚刚"
        delta < hour -> "${delta / minute}分钟前"
        delta < day -> "${delta / hour}小时前"
        delta < 7 * day -> "${delta / day}天前"
        else -> java.text.SimpleDateFormat("M月d日", java.util.Locale.CHINA).format(java.util.Date(timestamp))
    }
}
