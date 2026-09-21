package app.moshu.journal.ui.components

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

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
fun MoShuSectionTitle(title: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        if (action != null && onAction != null) {
            // 用 TextButton 而不是裸 Text + clickable：后者只有约 36dp，够不到 48dp 的最小触控区。
            TextButton(onClick = onAction, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(action, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
fun MoShuEmptyState(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector = Icons.Rounded.AutoAwesome,
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
    }
}

@Composable
fun EntryCard(
    entry: EntryEntity,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    attachments: List<AttachmentEntity> = emptyList(),
    /** 整理失败时卡片内的重试入口；不传则只保留「整理失败」文案。 */
    onRetryAi: (() -> Unit)? = null,
) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().border(0.6.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.large),
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
                Text(entry.content, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.88f), maxLines = 4, overflow = TextOverflow.Ellipsis)
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
}

@Composable
fun EntryImageStrip(
    attachments: List<AttachmentEntity>,
    modifier: Modifier = Modifier,
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
                )
                // 多于三张时给出提示，否则 6 张的条目看起来和 3 张的一模一样。
                if (extra > 0 && index == shown.lastIndex) {
                    Box(
                        Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.45f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("+$extra", color = Color.White, style = MaterialTheme.typography.titleMedium)
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
) {
    var bitmap by remember(path) { mutableStateOf(imageCache.get(path)) }
    LaunchedEffect(path) {
        if (bitmap != null) return@LaunchedEffect
        val loaded = withContext(Dispatchers.IO) {
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
                // 按显示尺寸下采样，避免列表中解码全尺寸原图造成卡顿与内存压力
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= 720 && bounds.outHeight / (sample * 2) >= 720) sample *= 2
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
        Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Image, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun AiStateLabel(state: String, onOpen: () -> Unit, onRetry: (() -> Unit)?) {
    val entryState = EntryAiState.from(state)
    val label = when (entryState) {
        EntryAiState.PENDING -> "等待整理"
        EntryAiState.RUNNING -> "整理中"
        EntryAiState.FAILED -> "整理失败"
        EntryAiState.IDLE -> "本地记录"
        EntryAiState.SUCCEEDED -> return
    }
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
            IconButton(onClick = onRetry, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Rounded.Refresh,
                    contentDescription = "重新整理",
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

fun moodEmoji(mood: String): String = when (mood) {
    "great" -> "✨"
    "good" -> "🙂"
    "neutral" -> "😌"
    "low" -> "😕"
    "bad" -> "😞"
    else -> ""
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
