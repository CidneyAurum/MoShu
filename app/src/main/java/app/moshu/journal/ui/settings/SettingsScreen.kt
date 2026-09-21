package app.moshu.journal.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.moshu.journal.BuildConfig
import app.moshu.journal.reminder.Notifications
import app.moshu.journal.reminder.ReminderScheduler
import app.moshu.journal.ui.components.MoShuPageHeader
import java.util.Locale
import kotlinx.coroutines.launch
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showAdvanced by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    var showSoundDialog by remember { mutableStateOf(false) }
    var restoreUri by remember { mutableStateOf<Uri?>(null) }
    var replaceUri by remember { mutableStateOf<Uri?>(null) }

    val backupExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri -> uri?.let(viewModel::exportBackup) }
    val markdownExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri -> uri?.let(viewModel::exportMarkdown) }
    val restorePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { if (it != null) restoreUri = it }
    val soundPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            viewModel.saveReminderSound(uri.toString())
            Notifications.applyChannelSound(context, uri.toString())
        }
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            viewModel.setReminder(true, state.reminderHour, state.reminderMinute)
            ReminderScheduler.schedule(context, state.reminderHour, state.reminderMinute)
        }
    }

    fun toggleReminder(enabled: Boolean) {
        if (!enabled) {
            viewModel.setReminder(false, state.reminderHour, state.reminderMinute)
            ReminderScheduler.cancel(context)
            return
        }
        val granted = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            viewModel.setReminder(true, state.reminderHour, state.reminderMinute)
            ReminderScheduler.schedule(context, state.reminderHour, state.reminderMinute)
        } else notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { MoShuPageHeader("设置", "让墨枢按你的方式工作", onBack = onBack) }
        item {
            SettingsCard("外观", Icons.Rounded.Palette) {
                Text("主题", style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色").forEach { (mode, label) ->
                        FilterChip(state.themeMode == mode, { viewModel.setTheme(mode) }, { Text(label) })
                    }
                }
            }
        }
        item {
            SettingsCard("AI 服务", Icons.Rounded.AutoAwesome) {
                Text("墨枢直连你选择的 OpenAI 兼容接口，支持服务商基础地址与完整请求端点。密钥只加密保存在本机。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.templates) { template ->
                        val applied = state.baseUrl.trim() == template.url && state.model.trim() == template.model
                        FilterChip(applied, { viewModel.applyTemplate(template) }, { Text(template.label) })
                    }
                }
                OutlinedTextField(state.baseUrl, viewModel::onBaseUrlChange, Modifier.fillMaxWidth(), label = { Text(if (state.exactEndpoint) "完整请求端点" else "服务地址") }, placeholder = { Text(if (state.exactEndpoint) "https://api.example.com/custom/chat" else "https://api.example.com/v1") }, singleLine = true)
                Text(
                    if (state.exactEndpoint) "将按上方地址直接发送 OpenAI 兼容请求。" else "可填写根地址、带版本路径，或完整的 /chat/completions 地址。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(state.model, viewModel::onModelChange, Modifier.fillMaxWidth(), label = { Text("文本模型") }, singleLine = true)
                OutlinedTextField(
                    state.apiKey,
                    viewModel::onApiKeyChange,
                    Modifier.fillMaxWidth(),
                    label = { Text(if (state.hasStoredKey) "API Key（已保存，留空不变）" else "API Key") },                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
                if (state.keyUnreadable) {
                    Text(
                        "已保存的 API Key 无法解密（通常是更换了锁屏方式，或从备份恢复到了新设备）。请重新填写一次密钥。",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("允许图片参与 AI 整理", style = MaterialTheme.typography.bodyMedium)
                        Text("默认关闭；开启后图片会发送至你配置的视觉模型。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(state.allowImageAnalysis, viewModel::onAllowImagesChange)
                }
                AnimatedVisibility(state.allowImageAnalysis) {
                    OutlinedTextField(state.visionModel, viewModel::onVisionModelChange, Modifier.fillMaxWidth(), label = { Text("视觉模型") }, placeholder = { Text("例如 gpt-4o-mini") }, singleLine = true)
                }
                Row(Modifier.fillMaxWidth().clickable(onClickLabel = "展开高级鉴权设置") { showAdvanced = !showAdvanced }.heightIn(min = 48.dp).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("高级鉴权设置", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                    Icon(Icons.Rounded.ChevronRight, if (showAdvanced) "收起" else "展开")
                }
                AnimatedVisibility(showAdvanced) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("使用完整请求端点", style = MaterialTheme.typography.bodyMedium)
                                Text("适合路径非标准的兼容网关；开启后不再自动补全地址。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(state.exactEndpoint, viewModel::onExactEndpointChange)
                        }
                        OutlinedTextField(state.authHeaderName, viewModel::onAuthHeaderChange, Modifier.fillMaxWidth(), label = { Text("鉴权 Header") }, singleLine = true)
                        OutlinedTextField(state.authPrefix, viewModel::onAuthPrefixChange, Modifier.fillMaxWidth(), label = { Text("值前缀") }, singleLine = true)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = viewModel::save, enabled = !state.saving) {
                        if (state.saving) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("保存配置")
                    }
                    OutlinedButton(onClick = viewModel::testConnection, enabled = !state.testing) { Text(if (state.testing) "测试中…" else "测试连接") }
                }
                if (state.message.isNotBlank()) Text(state.message, style = MaterialTheme.typography.bodySmall, color = if (state.messageOk) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error)
            }
        }
        item {
            SettingsCard("提醒", Icons.Rounded.Notifications) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("每日未写提醒", style = MaterialTheme.typography.bodyMedium)
                        Text("当天已经记录就不打扰。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(state.reminderEnabled, ::toggleReminder)
                }
                OutlinedButton(onClick = { showTimePicker = true }, enabled = state.reminderEnabled, modifier = Modifier.fillMaxWidth()) {
                    Text("提醒时间  %02d:%02d".format(Locale.CHINA, state.reminderHour, state.reminderMinute))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("提醒铃声", style = MaterialTheme.typography.bodyMedium)
                        Text(Notifications.soundLabel(context, state.reminderSound), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { showSoundDialog = true }) { Text("更换") }
                }
            }
        }
        item {
            SettingsCard("数据", Icons.Rounded.Archive) {
                Text("数据与图片保存在本机。完整备份不加密，也绝不包含 API Key。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(
                    onClick = { backupExport.launch("墨枢备份-${LocalDate.now()}.moshu") },
                    enabled = !state.dataBusy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Icon(Icons.Rounded.Download, null); Spacer(Modifier.size(8.dp)); Text("导出完整备份") }
                OutlinedButton(
                    onClick = { markdownExport.launch("墨枢记忆-${LocalDate.now()}.md") },
                    enabled = !state.dataBusy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Icon(Icons.Rounded.Download, null); Spacer(Modifier.size(8.dp)); Text("导出可读 Markdown") }
                OutlinedButton(
                    // 不要带 "*/*"：通配符会覆盖前面的具体类型，选择器就变成「任意文件」了。
                    onClick = { restorePicker.launch(arrayOf("application/zip", "application/octet-stream")) },
                    enabled = !state.dataBusy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Icon(Icons.Rounded.Upload, null); Spacer(Modifier.size(8.dp)); Text("恢复备份") }
                if (state.dataBusy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                if (state.dataMessage.isNotBlank()) Text(state.dataMessage, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            }
        }
        item {
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("墨枢 ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelLarge)
                Text("本地优先 · 你的记忆只属于你", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    if (showTimePicker) {
        val picker = rememberTimePickerState(state.reminderHour, state.reminderMinute, true)
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            title = { Text("选择提醒时间") },
            text = { TimePicker(picker) },
            confirmButton = { TextButton(onClick = { viewModel.setReminder(state.reminderEnabled, picker.hour, picker.minute); if (state.reminderEnabled) ReminderScheduler.schedule(context, picker.hour, picker.minute); showTimePicker = false }) { Text("确定") } },
            dismissButton = { TextButton(onClick = { showTimePicker = false }) { Text("取消") } },
        )
    }
    if (showSoundDialog) {
        AlertDialog(
            onDismissRequest = { showSoundDialog = false },
            title = { Text("提醒铃声") },
            text = {
                Column {
                    TextButton(onClick = { viewModel.saveReminderSound(Notifications.SOUND_DEFAULT); Notifications.applyChannelSound(context, Notifications.SOUND_DEFAULT); showSoundDialog = false }) { Text("系统默认") }
                    TextButton(onClick = { viewModel.saveReminderSound(Notifications.SOUND_SILENT); Notifications.applyChannelSound(context, Notifications.SOUND_SILENT); showSoundDialog = false }) { Text("静音") }
                    TextButton(onClick = { showSoundDialog = false; soundPicker.launch(arrayOf("audio/*")) }) { Text("选择音频文件…") }
                }
            },
            confirmButton = {},
        )
    }
    restoreUri?.let { uri ->
        AlertDialog(
            onDismissRequest = { restoreUri = null },
            title = { Text("恢复墨枢备份") },
            text = {
                Column {
                    Text("建议选择“合并恢复”，相同记忆会自动跳过。")
                    TextButton(onClick = { replaceUri = uri; restoreUri = null }) {
                        Text("改为清空后恢复", color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = { Button(onClick = { viewModel.restore(uri, false); restoreUri = null }) { Text("合并恢复") } },
            dismissButton = { TextButton(onClick = { restoreUri = null }) { Text("取消") } },
        )
    }
    replaceUri?.let { uri ->
        AlertDialog(
            onDismissRequest = { replaceUri = null },
            title = { Text("确认清空本机数据？") },
            text = { Text("现有记忆、图片、行动和回顾会被删除，再从备份恢复。此操作无法撤销。") },
            confirmButton = { Button(onClick = { viewModel.restore(uri, true); replaceUri = null }) { Text("清空并恢复") } },
            dismissButton = { TextButton(onClick = { replaceUri = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun SettingsCard(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.size(10.dp))
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            content()
        }
    }
}
