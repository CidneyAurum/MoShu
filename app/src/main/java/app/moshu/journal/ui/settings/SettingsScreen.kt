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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MenuBook
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Sell
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.moshu.journal.BuildConfig
import app.moshu.journal.ai.Hosts
import app.moshu.journal.ai.UrlNormalizer
import app.moshu.journal.reminder.Notifications
import app.moshu.journal.reminder.ReminderScheduler
import app.moshu.journal.ui.components.MessageSeverity
import app.moshu.journal.ui.components.MoShuMessageBar
import app.moshu.journal.ui.components.MoShuPageHeader
import java.util.Locale
import kotlinx.coroutines.launch
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /** 重新查看使用引导。引导页本身是可返回的，不影响 onboardingDone。 */
    onOpenOnboarding: () -> Unit = {},
    onOpenTags: () -> Unit = {},
    onOpenTrash: () -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showAdvanced by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    var showSoundDialog by remember { mutableStateOf(false) }
    var showKey by remember { mutableStateOf(false) }
    var modelFilter by remember { mutableStateOf("") }
    var restoreUri by remember { mutableStateOf<Uri?>(null) }
    var replaceUri by remember { mutableStateOf<Uri?>(null) }
    val apiKeyFocus = remember { FocusRequester() }
    // 校验失败时把焦点移到 API Key 输入框，否则用户只看到一条提示却不知道该改哪里。
    LaunchedEffect(state.apiKeyError) {
        if (state.apiKeyError.isNotBlank()) runCatching { apiKeyFocus.requestFocus() }
    }

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
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("跟随壁纸取色", style = MaterialTheme.typography.bodyMedium)
                            Text("用系统壁纸的主色替换品牌配色。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(state.dynamicColor, viewModel::setDynamicColor)
                    }
                }
            }
        }
        item {
            SettingsCard("AI 服务", Icons.Rounded.AutoAwesome) {
                Text("墨枢直连你选择的 OpenAI 兼容接口，支持服务商基础地址与完整请求端点。密钥只加密保存在本机。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                // 成本可见性：不写清楚的话，导入 200 条记录会悄悄触发 200 次计费调用。
                Text("每条新记录都会调用一次模型（开启图片理解时可能两次）。", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    "发送到服务商的内容：记录正文（整理）、你的问题与相关摘录（问墨枢）、近 7 天摘录（提醒文案）、图片（仅在开启时）。当前服务商：${hostOf(state.baseUrl)}。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    if (state.usage.totalCalls == 0) "本机还没有调用记录。"
                    else "本月约 ${state.usage.monthCalls} 次调用 · ${state.usage.monthTokens} tokens（累计 ${state.usage.totalCalls} 次）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (state.usage.totalCalls > 0) {
                    TextButton(onClick = viewModel::resetUsage) { Text("重置统计") }
                }
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
                // 归一化后的真实请求地址：路径猜错以前要到 404 才会发现。
                val resolvedUrl = runCatching { UrlNormalizer.requestUrl(state.baseUrl, state.exactEndpoint) }.getOrNull()
                if (!resolvedUrl.isNullOrBlank()) {
                    Text("将请求：$resolvedUrl", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                }
                if (state.hint.isNotBlank()) {
                    Text(state.hint, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                OutlinedTextField(state.model, viewModel::onModelChange, Modifier.fillMaxWidth(), label = { Text("文本模型") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = viewModel::fetchModels, enabled = !state.loadingModels) {
                        if (state.loadingModels) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Text("获取模型列表")
                    }
                    if (state.discoveredModels.isNotEmpty()) {
                        Text("${state.discoveredModels.size} 个可选", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (state.discoveredModels.isNotEmpty()) {
                    // 模型名拼错是 BYOK 最常见的失败原因，能点选就不要手打。
                    val shown = state.discoveredModels.filter { it.contains(modelFilter, ignoreCase = true) }
                    OutlinedTextField(
                        value = modelFilter,
                        onValueChange = { modelFilter = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("筛选模型") },
                        placeholder = { Text("例如 flash") },
                        singleLine = true,
                        trailingIcon = {
                            if (modelFilter.isNotBlank()) {
                                IconButton(onClick = { modelFilter = "" }) { Icon(Icons.Rounded.Close, "清空筛选") }
                            }
                        },
                    )
                    Text(
                        // 之前只展示前 40 个，靠后的模型永远点不到；现在给出真实数量。
                        "共 ${state.discoveredModels.size} 个，显示 ${shown.size} 个",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        shown.take(60).forEach { id ->
                            FilterChip(state.model.trim() == id, { viewModel.selectModel(id) }, { Text(id) })
                        }
                    }
                }
                OutlinedTextField(
                    state.apiKey,
                    viewModel::onApiKeyChange,
                    Modifier.fillMaxWidth().focusRequester(apiKeyFocus),
                    label = { Text(if (state.hasStoredKey) "API Key（已保存，留空不变）" else "API Key") },
                    singleLine = true,
                    // 粘贴后无法核对是否漏字符，所以给一个显式的明文开关（默认仍是密文）。
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showKey = !showKey }) {
                            Icon(
                                if (showKey) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                contentDescription = if (showKey) "隐藏密钥" else "显示密钥",
                            )
                        }
                    },
                    isError = state.apiKeyError.isNotBlank(),
                    // 与今日页的计数器一致：始终提供 supportingText 槽位，只在有错时渲染内容。
                    supportingText = { if (state.apiKeyError.isNotBlank()) Text(state.apiKeyError) },
                )
                if (state.hasStoredKey) {
                    // 留空输入框的语义是「保持原密钥」，所以轮换/撤销必须有个显式入口。
                    TextButton(onClick = viewModel::clearAiKey) { Text("清除密钥", color = MaterialTheme.colorScheme.error) }
                }
                if (state.hostChanged) {
                    Text(
                        "服务地址已更改，请重新确认密钥",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
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
                        OutlinedTextField(
                            state.authPrefix,
                            viewModel::onAuthPrefixChange,
                            Modifier.fillMaxWidth(),
                            label = { Text("值前缀（含尾部空格）") },
                            singleLine = true,
                            supportingText = { Text("例如 \"Bearer \"（Bearer 后有一个空格）") },
                        )
                        // 前缀里的空格肉眼看不见，拼错就是一连串查不出原因的 401，所以直接展示拼装结果。
                        val keyPreview = maskedKey(state.apiKey, state.hasStoredKey)
                        Text(
                            "将发送：${state.authHeaderName.ifBlank { "Authorization" }}: ${state.authPrefix}$keyPreview",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                        if (state.authPrefix.isNotBlank() && !state.authPrefix.endsWith(" ")) {
                            Text(
                                "前缀末尾没有空格，会拼成「${state.authPrefix}$keyPreview」；如服务商要求空格请补上。",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = viewModel::save, enabled = !state.saving) {
                        if (state.saving) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("保存配置")
                    }
                    OutlinedButton(onClick = viewModel::testConnection, enabled = !state.testing) { Text(if (state.testing) "测试中…" else "测试连接") }
                }
                if (state.message.isNotBlank()) MoShuMessageBar(state.message, severity = if (state.messageOk) MessageSeverity.INFO else MessageSeverity.ERROR)
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
                if (state.reminderEnabled) {
                    // 只显示「21:30」看不出下一次到底什么时候响。
                    Text(
                        nextReminderLabel(state.reminderHour, state.reminderMinute),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
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
            SettingsCard("整理", Icons.Rounded.Sell) {
                Text(
                    "标签由 AI 整理时补全。这里可以改名、合并同义标签，或把某个标签从所有记录上摘掉（不会删除记录）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = onOpenTags, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.Sell, null); Spacer(Modifier.size(8.dp)); Text("管理标签")
                }
                OutlinedButton(onClick = onOpenTrash, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.DeleteOutline, null); Spacer(Modifier.size(8.dp)); Text("回收站")
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
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = viewModel::checkIntegrity,
                        enabled = !state.dataBusy,
                        modifier = Modifier.weight(1f),
                    ) { Text("数据自检") }
                    OutlinedButton(
                        onClick = viewModel::repairIntegrity,
                        enabled = !state.dataBusy,
                        modifier = Modifier.weight(1f),
                    ) { Text("修复") }
                }
                Text(
                    "自检会检查孤儿图片、指向已删除记忆的行动、非法分类。修复只做无争议的清理，不会删除你的记录。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            SettingsCard("关于与隐私", Icons.Rounded.Lock) {
                Text("数据存在哪里", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "正文、图片、行动与回顾都保存在本机应用私有目录，没有墨枢账号，也没有墨枢服务器。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text("AI 会发送什么", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "开启 AI 后：整理会发送记录正文；问墨枢会发送你的问题与命中的摘录；每日提醒会发送近 7 天摘录；" +
                        "图片默认不发送，需在「AI 服务」里单独开启。除此之外不会上传任何内容。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text("密钥怎么保存", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "API Key 用 Android Keystore 的 AES/GCM 加密后存在本机，导出备份绝不包含密钥。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text("如何彻底删除", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "逐条删除记忆，或在「数据」里用清空并恢复的方式重建；卸载应用会一并清除本机全部数据。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = onOpenOnboarding, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.MenuBook, null)
                    Spacer(Modifier.size(8.dp))
                    Text("查看使用引导")
                }
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
        ReminderSoundDialog(
            context = context,
            current = state.reminderSound,
            onPick = { mode ->
                viewModel.saveReminderSound(mode)
                Notifications.applyChannelSound(context, mode)
                showSoundDialog = false
            },
            onPickFile = {
                showSoundDialog = false
                soundPicker.launch(arrayOf("audio/*"))
            },
            onDismiss = { showSoundDialog = false },
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

/**
 * 提醒铃声选择器。原先只是三个裸文字按钮：看不出当前选中的是哪个，
 * 也没法试听——而铃声恰恰是「不试听就选错」的典型设置。
 */
@Composable
private fun ReminderSoundDialog(
    context: android.content.Context,
    current: String,
    onPick: (String) -> Unit,
    onPickFile: () -> Unit,
    onDismiss: () -> Unit,
) {
    var playing by remember { mutableStateOf<android.media.Ringtone?>(null) }

    fun preview(mode: String) {
        playing?.runCatching { stop() }
        playing = null
        val uri = when (mode) {
            Notifications.SOUND_DEFAULT -> android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION)
            Notifications.SOUND_SILENT -> null
            else -> android.net.Uri.parse(mode)
        } ?: return
        playing = runCatching { android.media.RingtoneManager.getRingtone(context, uri) }.getOrNull()
        playing?.play()
    }

    // 对话框关掉就停掉试听，否则铃声会一直响。
    DisposableEffect(Unit) { onDispose { playing?.runCatching { stop() } } }

    AlertDialog(
        onDismissRequest = { playing?.runCatching { stop() }; onDismiss() },
        title = { Text("提醒铃声") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(
                    Notifications.SOUND_DEFAULT to ("系统默认" to "使用系统通知音"),
                    Notifications.SOUND_SILENT to ("静音" to "只显示通知，不发声"),
                ).forEach { (mode, labels) ->
                    SoundOptionRow(
                        label = labels.first,
                        description = labels.second,
                        selected = current == mode,
                        onSelect = { onPick(mode) },
                        onPreview = { preview(mode) },
                    )
                }
                SoundOptionRow(
                    label = if (current.isNotEmpty() && current != Notifications.SOUND_SILENT) {
                        Notifications.soundLabel(context, current)
                    } else {
                        "自定义音频文件"
                    },
                    description = "从本机选择一段音频作为提醒音",
                    selected = current.isNotEmpty() && current != Notifications.SOUND_SILENT,
                    onSelect = onPickFile,
                    onPreview = { preview(current) },
                    previewEnabled = current.isNotEmpty() && current != Notifications.SOUND_SILENT,
                )
                Text(
                    "确定后立即生效；系统通知渠道会按新铃声重建。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = { playing?.runCatching { stop() }; onDismiss() }) { Text("完成") } },
    )
}

@Composable
private fun SoundOptionRow(
    label: String,
    description: String,
    selected: Boolean,
    onSelect: () -> Unit,
    onPreview: () -> Unit,
    previewEnabled: Boolean = true,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClickLabel = "选择$label") { onSelect() }.heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(description, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = onPreview, enabled = previewEnabled) {
            Icon(Icons.Rounded.PlayArrow, contentDescription = "试听$label")
        }
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

/** 展示用主机名；没写协议的地址也尽量解析，解析不出就原样显示。 */
private fun hostOf(url: String): String = Hosts.of(url).ifBlank { "未填写" }

/** 「下次提醒」文案：给出具体的星期与时刻，而不是只显示一个时间设置值。 */
private fun nextReminderLabel(hour: Int, minute: Int): String {
    val zone = java.time.ZoneId.systemDefault()
    val at = java.time.Instant.ofEpochMilli(ReminderScheduler.nextTriggerTime(hour, minute)).atZone(zone)
    val today = java.time.LocalDate.now(zone)
    val day = when (at.toLocalDate()) {
        today -> "今天"
        today.plusDays(1) -> "明天"
        else -> at.format(java.time.format.DateTimeFormatter.ofPattern("M月d日", Locale.CHINA))
    }
    return "下次提醒：$day %02d:%02d".format(Locale.CHINA, at.hour, at.minute)
}

/** 密钥只显示首尾各 4 位；已保存但不在输入框里的密钥不还原明文。 */
private fun maskedKey(apiKey: String, hasStored: Boolean): String = when {
    apiKey.isNotBlank() -> if (apiKey.length > 8) "${apiKey.take(4)}…${apiKey.takeLast(4)}" else "…"
    hasStored -> "已保存的密钥"
    else -> "sk-…"
}
