package app.moshu.journal.ui.calendar

import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccessTime
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.moshu.journal.data.db.EventEntity
import app.moshu.journal.data.db.Importance
import app.moshu.journal.data.db.RepeatRule
import app.moshu.journal.data.schedule.EventIntentParser
import app.moshu.journal.reminder.Notifications
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 编辑器产出的草稿；由调用方决定是新建还是更新。 */
data class EventDraft(
    val title: String,
    val note: String,
    val day: LocalDate,
    val minuteOfDay: Int,
    val allDay: Boolean,
    val repeat: String,
    val reminderOffsetMin: Int,
    val soundUri: String,
    val soundLabel: String,
    val importance: String,
)

/** 提醒提前量的预设档位。做成档位而不是滑杆：常用值就这几个，滑杆反而难对准。 */
private val REMINDER_OFFSETS = listOf(
    0 to "准点",
    5 to "提前 5 分钟",
    15 to "提前 15 分钟",
    30 to "提前 30 分钟",
    60 to "提前 1 小时",
    1440 to "提前 1 天",
)

/**
 * 事件编辑器。
 *
 * 铃铛部分是本页的重点：**每个事件可以有自己的提醒铃声**，
 * 因为「吃药」和「开会」用同一个提示音，用户根本分不清是哪个提醒响了。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EventEditorSheet(
    initialDay: LocalDate,
    initialEvent: EventEntity?,
    /** 由自然语言解析得到的草稿；解析不准时带进来改，不必让用户从头再填。 */
    initialIntent: EventIntentParser.Intent? = null,
    onDismiss: () -> Unit = {},
    onSave: (EventDraft) -> Unit,
) {
    val context = LocalContext.current
    val zone = remember { java.time.ZoneId.systemDefault() }

    var title by remember { mutableStateOf(initialEvent?.title ?: initialIntent?.title.orEmpty()) }
    var note by remember { mutableStateOf(initialEvent?.note.orEmpty()) }
    var day by remember {
        mutableStateOf(
            initialEvent?.let { Instant.ofEpochMilli(it.startAt).atZone(zone).toLocalDate() }
                ?: initialIntent?.day
                ?: initialDay,
        )
    }
    var minuteOfDay by remember {
        mutableIntStateOf(
            initialEvent?.let {
                val t = Instant.ofEpochMilli(it.startAt).atZone(zone).toLocalTime()
                t.hour * 60 + t.minute
            } ?: defaultMinuteOfDay(),
        )
    }
    var allDay by remember { mutableStateOf(initialEvent?.allDay ?: initialIntent?.allDay ?: false) }
    var repeat by remember { mutableStateOf(initialEvent?.repeatRule ?: initialIntent?.repeat ?: RepeatRule.NONE) }
    var reminderOffset by remember {
        mutableIntStateOf(initialEvent?.reminderOffsetMin ?: initialIntent?.reminderOffsetMin ?: EventEntity.NO_REMINDER)
    }
    var soundUri by remember { mutableStateOf(initialEvent?.soundUri.orEmpty()) }
    var soundLabel by remember { mutableStateOf(initialEvent?.soundLabel.orEmpty()) }
    var importance by remember { mutableStateOf(initialEvent?.importance ?: Importance.DEFAULT) }

    var pickingDate by remember { mutableStateOf(false) }
    var pickingTime by remember { mutableStateOf(false) }
    var pickingSound by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initialEvent == null) "新建安排" else "编辑安排") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("要做什么") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = day.format(DateTimeFormatter.ofPattern("yyyy年M月d日", Locale.CHINA)),
                        onValueChange = {},
                        readOnly = true,
                        enabled = false,
                        label = { Text("日期") },
                        leadingIcon = { Icon(Icons.Rounded.CalendarMonth, null) },
                        modifier = Modifier.weight(1.4f),
                    )
                    if (!allDay) {
                        OutlinedTextField(
                            value = "%02d:%02d".format(minuteOfDay / 60, minuteOfDay % 60),
                            onValueChange = {},
                            readOnly = true,
                            enabled = false,
                            label = { Text("时间") },
                            leadingIcon = { Icon(Icons.Rounded.AccessTime, null) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { pickingDate = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text("改日期") }
                    if (!allDay) {
                        TextButton(onClick = { pickingTime = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text("改时间") }
                    }
                }

                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("全天", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "全天事件在当天 00:00 触发，适合生日、纪念日",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = allDay, onCheckedChange = { allDay = it })
                }

                Text("重复", style = MaterialTheme.typography.labelLarge)
                // FlowRow 而不是 Row：五个档位在窄屏一行放不下，Row 会直接裁掉最后一个
                // （实测「每年」被切掉，用户以为没有这个选项）。
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    RepeatRule.ALL.forEach { rule ->
                        FilterChip(
                            selected = repeat == rule,
                            onClick = { repeat = rule },
                            label = { Text(RepeatRule.label(rule)) },
                        )
                    }
                }

                // ---- 提醒 ----
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (reminderOffset >= 0) Icons.Rounded.NotificationsActive else Icons.Rounded.NotificationsOff,
                        null,
                        tint = if (reminderOffset >= 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text("提醒", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            if (reminderOffset < 0) "不提醒" else REMINDER_OFFSETS.firstOrNull { it.first == reminderOffset }?.second
                                ?: "提前 $reminderOffset 分钟",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = reminderOffset >= 0,
                        onCheckedChange = { on -> reminderOffset = if (on) 0 else EventEntity.NO_REMINDER },
                    )
                }
                if (reminderOffset >= 0) {
                    // 六个提前量档位同样会溢出，一律换行。
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        REMINDER_OFFSETS.forEach { (offset, label) ->
                            FilterChip(
                                selected = reminderOffset == offset,
                                onClick = { reminderOffset = offset },
                                label = { Text(label) },
                            )
                        }
                    }

                    // ---- 铃声（每个事件独立） ----
                    Row(
                        Modifier.fillMaxWidth().clickable(onClickLabel = "选择提醒铃声") { pickingSound = true },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.PlayArrow, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text("提醒铃声", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                when {
                                    soundUri.isBlank() -> "系统默认"
                                    soundUri == Notifications.SOUND_SILENT -> "静音（只显示通知）"
                                    else -> soundLabel.ifBlank { "自定义铃声" }
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { pickingSound = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text("更换") }
                    }

                    Text("重要级别", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(
                            selected = importance == Importance.DEFAULT,
                            onClick = { importance = Importance.DEFAULT },
                            label = { Text("普通") },
                        )
                        FilterChip(
                            selected = importance == Importance.HIGH,
                            onClick = { importance = Importance.HIGH },
                            label = { Text("重要（横幅弹出）") },
                        )
                    }
                }

                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("备注（可选）") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        EventDraft(
                            title = title,
                            note = note,
                            day = day,
                            minuteOfDay = minuteOfDay,
                            allDay = allDay,
                            repeat = repeat,
                            reminderOffsetMin = reminderOffset,
                            soundUri = soundUri,
                            soundLabel = soundLabel,
                            importance = importance,
                        ),
                    )
                },
                enabled = title.isNotBlank(),
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )

    if (pickingDate) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = day.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(),
        )
        AlertDialog(
            onDismissRequest = { pickingDate = false },
            title = { Text("选择日期") },
            text = { DatePicker(pickerState) },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let {
                        day = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()
                    }
                    pickingDate = false
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { pickingDate = false }) { Text("取消") } },
        )
    }

    if (pickingTime) {
        val timeState = rememberTimePickerState(
            initialHour = minuteOfDay / 60,
            initialMinute = minuteOfDay % 60,
            is24Hour = true,
        )
        AlertDialog(
            onDismissRequest = { pickingTime = false },
            title = { Text("选择时间") },
            text = { TimePicker(timeState) },
            confirmButton = {
                TextButton(onClick = {
                    minuteOfDay = timeState.hour * 60 + timeState.minute
                    pickingTime = false
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { pickingTime = false }) { Text("取消") } },
        )
    }

    if (pickingSound) {
        EventSoundPicker(
            current = soundUri,
            onPick = { uri, label ->
                soundUri = uri
                soundLabel = label
                pickingSound = false
            },
            onDismiss = { pickingSound = false },
        )
    }
}

/** 默认时间：下一个整点。用户新建事件时多半是「过一会儿」要做的事。 */
private fun defaultMinuteOfDay(): Int {
    val now = java.time.LocalTime.now()
    val nextHour = (now.hour + 1) % 24
    return nextHour * 60
}

/**
 * 事件铃声选择器。
 *
 * 给出系统通知音列表 + 静音 + 本机音频文件三种来源，并且**每一项都能试听**：
 * 铃声名字往往看不出实际效果（「Chime」「Tiptoe」听感差别很大），
 * 不试听就只能靠猜。
 */
@Composable
internal fun EventSoundPicker(
    current: String,
    onPick: (uri: String, label: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var playing by remember { mutableStateOf<Ringtone?>(null) }

    fun stopPreview() {
        playing?.runCatching { stop() }
        playing = null
    }

    fun preview(uri: String) {
        stopPreview()
        if (uri == Notifications.SOUND_SILENT) return
        val target = if (uri.isBlank()) RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION) else Uri.parse(uri)
        playing = runCatching { RingtoneManager.getRingtone(context, target) }.getOrNull()
        playing?.play()
    }

    // 对话框关闭时必须停掉试听，否则铃声会一直响下去。
    DisposableEffect(Unit) { onDispose { stopPreview() } }

    val systemSounds = remember {
        runCatching {
            val manager = RingtoneManager(context)
            manager.setType(RingtoneManager.TYPE_NOTIFICATION or RingtoneManager.TYPE_ALARM)
            // 用 getRingtoneTitle 之外的路径取名字：RingtoneManager 没有公开的 title 方法，
            // 只能从它自己的 cursor 里按列读。
            val cursor = manager.cursor
            (0 until cursor.count).mapNotNull { index ->
                cursor.moveToPosition(index)
                val uri = manager.getRingtoneUri(index)?.toString() ?: return@mapNotNull null
                val nameIndex = cursor.getColumnIndex(android.provider.MediaStore.Audio.Media.TITLE)
                val title = if (nameIndex >= 0) cursor.getString(nameIndex) else null
                uri to (title?.takeIf { it.isNotBlank() } ?: "系统铃声")
            }
        }.getOrDefault(emptyList())
    }

    AlertDialog(
        onDismissRequest = { stopPreview(); onDismiss() },
        title = { Text("提醒铃声") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                SoundChoiceRow("系统默认", "跟随系统通知音", current.isBlank(), { preview("") }) { stopPreview(); onPick("", "系统默认") }
                SoundChoiceRow("静音", "只显示通知，不发声", current == Notifications.SOUND_SILENT, null) {
                    stopPreview(); onPick(Notifications.SOUND_SILENT, "静音")
                }
                if (systemSounds.isEmpty()) {
                    Text(
                        "没有读到系统铃声列表，可以用「系统默认」，或在设置页为每日提醒选择本机音频文件。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 6.dp),
                    )
                } else {
                    Text(
                        "系统铃声（点右侧可试听）",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                    )
                    systemSounds.forEach { (uri, label) ->
                        SoundChoiceRow(label, uri, current == uri, { preview(uri) }) {
                            stopPreview(); onPick(uri, label)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { stopPreview(); onDismiss() }) { Text("完成") } },
    )
}

@Composable
private fun SoundChoiceRow(
    label: String,
    description: String,
    selected: Boolean,
    onPreview: (() -> Unit)?,
    onSelect: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClickLabel = "选择 $label", onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
            if (description != label) {
                Text(
                    description,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
        if (onPreview != null) {
            IconButton(onClick = onPreview) { Icon(Icons.Rounded.PlayArrow, "试听 $label") }
        }
    }
}
