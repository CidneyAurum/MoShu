package app.moshu.journal.ui.todo

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.moshu.journal.data.db.TodoEntity
import app.moshu.journal.ui.components.MoShuEmptyState
import app.moshu.journal.ui.components.MoShuPageHeader
import app.moshu.journal.ui.components.MoShuSectionTitle
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

@Composable
fun TodosScreen(
    viewModel: TodosViewModel,
    onOpenSource: (Long) -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<TodoEntity?>(null) }
    var pendingDelete by remember { mutableStateOf<TodoEntity?>(null) }
    var confirmClearCompleted by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    val today = LocalDate.now().toEpochDay().toInt()
    // 一次遍历分组，避免对每个元素做两次线性 contains（原实现是 O(n²)）。
    val (overdue, todayItems, later) = remember(state.active, today) {
        val overdue = mutableListOf<TodoEntity>()
        val todayItems = mutableListOf<TodoEntity>()
        val later = mutableListOf<TodoEntity>()
        state.active.forEach { todo ->
            val due = todo.dueEpochDay
            when {
                due == null -> later += todo
                due < today -> overdue += todo
                due == today -> todayItems += todo
                else -> later += todo
            }
        }
        Triple(overdue, todayItems, later)
    }

    Column(modifier.fillMaxSize()) {
        MoShuPageHeader("行动", "${state.active.size} 件事等待推进", onSettings = onSettings)
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("新增一个行动") },
                singleLine = true,
                shape = MaterialTheme.shapes.large,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                // 没有 IME 动作时按回车只会收起键盘，用户会以为输入被忽略了。
                keyboardActions = KeyboardActions(onDone = { if (draft.isNotBlank()) { viewModel.add(draft); draft = "" } }),
            )
            IconButton(onClick = { viewModel.add(draft); draft = "" }, enabled = draft.isNotBlank()) {
                Icon(Icons.AutoMirrored.Rounded.Send, "添加")
            }
        }
        Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(!state.showCompleted, { viewModel.setShowCompleted(false) }, { Text("进行中") })
            FilterChip(state.showCompleted, { viewModel.setShowCompleted(true) }, { Text("已完成") })
            if (state.showCompleted && state.completed.isNotEmpty()) {
                Spacer(Modifier.weight(1f))
                // 这是删除数据的入口，触控区必须够大，避免误触。
                TextButton(onClick = { confirmClearCompleted = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("清空已完成", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.error)
                }
            }
        }

        if (state.message.isNotBlank()) {
            Text(
                state.message,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }

        val visibleEmpty = if (state.showCompleted) state.completed.isEmpty() else state.active.isEmpty()
        if (state.loading) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            }
        } else if (visibleEmpty) {
            MoShuEmptyState(
                if (state.showCompleted) "还没有完成记录" else "行动列表很轻",
                if (state.showCompleted) "完成一件事后，它会安静地留在这里。" else "直接添加，或在记录里写下计划让 AI 自动提取。",
                Modifier.weight(1f),
            )
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (state.showCompleted) {
                    item { MoShuSectionTitle("已完成") }
                    items(state.completed, key = { it.id }) { TodoCard(it, viewModel::toggle, { editing = it }, { pendingDelete = it }, onOpenSource) }
                } else {
                    todoSection("已逾期", overdue, true, viewModel, { editing = it }, { pendingDelete = it }, onOpenSource)
                    todoSection("今天", todayItems, false, viewModel, { editing = it }, { pendingDelete = it }, onOpenSource)
                    todoSection("以后", later, false, viewModel, { editing = it }, { pendingDelete = it }, onOpenSource)
                }
            }
        }
    }

    editing?.let { todo ->
        TodoEditDialog(todo, onDismiss = { editing = null }, onSave = { text, due, remind ->
            viewModel.save(todo, text, due, remind)
            editing = null
        })
    }

    // 提示只对本次操作有意义：再次打开编辑框时清掉，否则它会一直挂在页面上像当前错误。
    LaunchedEffect(editing) { if (editing != null) viewModel.clearMessage() }

    pendingDelete?.let { todo ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除这条行动？") },
            text = { Text("「${todo.text.take(40)}」将被移除，提醒也会一并取消。") },
            confirmButton = {
                Button(onClick = { viewModel.delete(todo); pendingDelete = null }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } },
        )
    }

    if (confirmClearCompleted) {
        AlertDialog(
            onDismissRequest = { confirmClearCompleted = false },
            title = { Text("清空全部已完成？") },
            text = { Text("共 ${state.completed.size} 条已完成行动会被删除，此操作无法撤销。") },
            confirmButton = {
                Button(onClick = { viewModel.clearCompleted(); confirmClearCompleted = false }) { Text("全部删除") }
            },
            dismissButton = { TextButton(onClick = { confirmClearCompleted = false }) { Text("取消") } },
        )
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.todoSection(
    title: String,
    values: List<TodoEntity>,
    danger: Boolean,
    viewModel: TodosViewModel,
    onEdit: (TodoEntity) -> Unit,
    onDelete: (TodoEntity) -> Unit,
    onOpenSource: (Long) -> Unit,
) {
    if (values.isEmpty()) return
    item { Text("$title · ${values.size}", style = MaterialTheme.typography.titleSmall, color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp)) }
    items(values, key = { it.id }) { TodoCard(it, viewModel::toggle, { onEdit(it) }, onDelete, onOpenSource) }
}

@Composable
private fun TodoCard(todo: TodoEntity, onToggle: (TodoEntity) -> Unit, onEdit: () -> Unit, onDelete: (TodoEntity) -> Unit, onOpenSource: (Long) -> Unit) {
    val today = LocalDate.now().toEpochDay().toInt()
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onEdit),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(todo.done, { onToggle(todo) })
            Column(Modifier.weight(1f).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(todo.text, style = MaterialTheme.typography.bodyLarge, textDecoration = if (todo.done) TextDecoration.LineThrough else null)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    todo.dueEpochDay?.let { due ->
                        Icon(Icons.Rounded.CalendarMonth, null, Modifier.size(14.dp), tint = if (!todo.done && due < today) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.size(4.dp))
                        Text(LocalDate.ofEpochDay(due.toLong()).format(DateTimeFormatter.ofPattern("M月d日")), style = MaterialTheme.typography.labelSmall, color = if (!todo.done && due < today) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (todo.sourceEntryId > 0) {
                        Spacer(Modifier.size(10.dp))
                        Text(
                            "来自记忆",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier
                                .clickable(onClickLabel = "打开来源记忆") { onOpenSource(todo.sourceEntryId) }
                                .heightIn(min = 48.dp)
                                .wrapContentHeight(Alignment.CenterVertically)
                                .padding(horizontal = 4.dp),
                        )
                    }
                }
            }
            IconButton(onClick = { onDelete(todo) }) { Icon(Icons.Rounded.DeleteOutline, "删除", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TodoEditDialog(todo: TodoEntity, onDismiss: () -> Unit, onSave: (String, Int?, Boolean) -> Unit) {
    var text by remember(todo.id) { mutableStateOf(todo.text) }
    var due by remember(todo.id) { mutableStateOf(todo.dueEpochDay) }
    var remind by remember(todo.id) { mutableStateOf(todo.reminderAt != null) }
    var pickingDate by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) remind = true else remind = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑行动") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), label = { Text("要做什么") }, minLines = 2)
                Box(Modifier.fillMaxWidth().clickable(onClickLabel = "选择截止日期") { pickingDate = true }) {
                    OutlinedTextField(
                        value = due?.let { LocalDate.ofEpochDay(it.toLong()).format(DateTimeFormatter.ofPattern("yyyy年M月d日")) }.orEmpty(),
                        onValueChange = {},
                        readOnly = true,
                        // 必须 enabled = false：启用的输入框会吃掉指针事件，
                        // 外层 clickable 永远收不到点击，日期选择器就打不开了。
                        enabled = false,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("截止日期") },
                        placeholder = { Text("未设置") },
                        trailingIcon = { Icon(Icons.Rounded.CalendarMonth, null) },
                        colors = OutlinedTextFieldDefaults.colors(
                            disabledTextColor = MaterialTheme.colorScheme.onSurface,
                            disabledBorderColor = MaterialTheme.colorScheme.outline,
                            disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            disabledPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            disabledTrailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("当天 09:00 提醒", style = MaterialTheme.typography.bodyMedium)
                        Text("使用本机通知，不上传任务内容", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = remind,
                        onCheckedChange = { checked ->
                            if (!checked) {
                                remind = false
                            } else {
                                val granted = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
                                    context, Manifest.permission.POST_NOTIFICATIONS,
                                ) == PackageManager.PERMISSION_GRANTED
                                // 先要通知权限：没有它待办提醒会静默失败，用户会以为已经设好了。
                                if (granted) remind = true else permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        },
                        enabled = due != null,
                    )
                }
            }
        },
        confirmButton = { Button(onClick = { onSave(text, due, remind) }, enabled = text.isNotBlank()) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )

    if (pickingDate) {
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = due?.let { LocalDate.ofEpochDay(it.toLong()).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli() })
        AlertDialog(
            onDismissRequest = { pickingDate = false },
            title = { Text("选择截止日期") },
            text = { DatePicker(pickerState) },
            confirmButton = {
                TextButton(onClick = {
                    due = pickerState.selectedDateMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toEpochDay().toInt() }
                    pickingDate = false
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { due = null; remind = false; pickingDate = false }) { Text("清除日期") } },
        )
    }
}
