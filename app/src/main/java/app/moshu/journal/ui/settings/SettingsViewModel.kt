package app.moshu.journal.ui.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.moshu.journal.MoShuApp
import app.moshu.journal.ai.AiClient
import app.moshu.journal.ai.AiConfig
import app.moshu.journal.data.backup.BackupManager
import app.moshu.journal.reminder.TodoReminderWorker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsUiState(
    val templates: List<AiConfig.Template> = AiConfig.TEMPLATES,
    val baseUrl: String = "",
    val model: String = "",
    val visionModel: String = "",
    val allowImageAnalysis: Boolean = false,
    val apiKey: String = "",
    val authHeaderName: String = "Authorization",
    val authPrefix: String = "Bearer ",
    val exactEndpoint: Boolean = false,
    val hasStoredKey: Boolean = false,
    /** 密文在但解不开：需要提示用户重新填写密钥，而不是显示成「尚未配置」。 */
    val keyUnreadable: Boolean = false,
    val saving: Boolean = false,
    val testing: Boolean = false,
    val message: String = "",
    val messageOk: Boolean = false,
    val reminderEnabled: Boolean = false,
    val reminderHour: Int = 21,
    val reminderMinute: Int = 30,
    val reminderSound: String = "",
    val themeMode: String = "system",
    val dataBusy: Boolean = false,
    val dataMessage: String = "",
)

class SettingsViewModel : ViewModel() {
    private val app = MoShuApp.instance
    private val settings = app.settings
    private val draft = MutableStateFlow(Draft())
    private val saving = MutableStateFlow(false)
    private val testing = MutableStateFlow(false)
    private val message = MutableStateFlow("" to false)
    private val dataBusy = MutableStateFlow(false)
    private val dataMessage = MutableStateFlow("")

    data class Draft(
        val baseUrl: String = "",
        val model: String = "",
        val visionModel: String = "",
        val allowImageAnalysis: Boolean = false,
        val apiKey: String = "",
        val authHeaderName: String = "Authorization",
        val authPrefix: String = "Bearer ",
        val exactEndpoint: Boolean = false,
        val hasStoredKey: Boolean = false,
        val loaded: Boolean = false,
        /** 用户是否已经改过任何字段：改过之后就不再被 DataStore 的首次发射覆盖。 */
        val touched: Boolean = false,
    )

    private data class Core(val draft: Draft, val saving: Boolean, val testing: Boolean, val message: Pair<String, Boolean>)
    private data class Peripheral(val enabled: Boolean, val hour: Int, val minute: Int, val sound: String, val theme: String)
    private data class DataState(val busy: Boolean, val message: String)

    private val core = combine(draft, saving, testing, message) { d, s, t, m -> Core(d, s, t, m) }
    private val peripheral = combine(settings.reminderEnabled, settings.reminderTime, settings.reminderSound, settings.themeMode) { enabled, time, sound, theme ->
        Peripheral(enabled, time.first, time.second, sound, theme)
    }
    private val dataState = combine(dataBusy, dataMessage) { busy, note -> DataState(busy, note) }

    val uiState: StateFlow<SettingsUiState> = combine(core, peripheral, dataState, settings.keyUnreadable) { c, p, data, unreadable ->
        SettingsUiState(
            baseUrl = c.draft.baseUrl,
            model = c.draft.model,
            visionModel = c.draft.visionModel,
            allowImageAnalysis = c.draft.allowImageAnalysis,
            apiKey = c.draft.apiKey,
            authHeaderName = c.draft.authHeaderName,
            authPrefix = c.draft.authPrefix,
            exactEndpoint = c.draft.exactEndpoint,
            hasStoredKey = c.draft.hasStoredKey,
            keyUnreadable = unreadable,
            saving = c.saving,
            testing = c.testing,
            message = c.message.first,
            messageOk = c.message.second,
            reminderEnabled = p.enabled,
            reminderHour = p.hour,
            reminderMinute = p.minute,
            reminderSound = p.sound,
            themeMode = p.theme,
            dataBusy = data.busy,
            dataMessage = data.message,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    init {
        viewModelScope.launch {
            settings.aiConfig.collect { config ->
                // 只在用户还没动过任何字段时回填。曾经的判断是 !loaded，而每个
                // onXxxChange 都用 copy 保留了 loaded=false，于是刚打开页面就输入的内容
                // 会被第一次 DataStore 发射覆盖掉。
                if (!draft.value.touched) {
                    draft.value = Draft(
                        baseUrl = config.baseUrl,
                        model = config.model,
                        visionModel = config.visionModel,
                        allowImageAnalysis = config.allowImageAnalysis,
                        authHeaderName = config.authHeaderName.ifBlank { "Authorization" },
                        authPrefix = config.authPrefix,
                        exactEndpoint = config.exactEndpoint,
                        hasStoredKey = config.apiKey.isNotBlank(),
                        loaded = true,
                        touched = false,
                    )
                }
            }
        }
    }

    fun applyTemplate(template: AiConfig.Template) { draft.value = draft.value.copy(baseUrl = template.url, model = template.model, exactEndpoint = false, touched = true) }
    fun onBaseUrlChange(value: String) { draft.value = draft.value.copy(baseUrl = value, touched = true) }
    fun onModelChange(value: String) { draft.value = draft.value.copy(model = value, touched = true) }
    fun onVisionModelChange(value: String) { draft.value = draft.value.copy(visionModel = value, touched = true) }
    fun onAllowImagesChange(value: Boolean) { draft.value = draft.value.copy(allowImageAnalysis = value, touched = true) }
    fun onApiKeyChange(value: String) { draft.value = draft.value.copy(apiKey = value.trim(), touched = true) }
    fun onAuthHeaderChange(value: String) { draft.value = draft.value.copy(authHeaderName = value.trim(), touched = true) }
    fun onAuthPrefixChange(value: String) { draft.value = draft.value.copy(authPrefix = value.trim(), touched = true) }
    fun onExactEndpointChange(value: Boolean) { draft.value = draft.value.copy(exactEndpoint = value, touched = true) }

    fun save() {
        val d = draft.value
        if (d.baseUrl.isBlank() || d.model.isBlank()) return setMessage("Base URL 与文本模型不能为空", false)
        if (d.allowImageAnalysis && d.visionModel.isBlank()) return setMessage("开启图片理解前，请填写视觉模型", false)
        viewModelScope.launch {
            saving.value = true
            try {
                val key = d.apiKey.ifBlank { settings.currentAiConfig().apiKey }
                // 密钥为空就明确要求填写：原来会照样报「已保存」，之后每次调用都只得到
                // 一个鉴权错误，用户完全看不出根因（换锁屏或恢复备份后尤其常见）。
                if (key.isBlank()) {
                    setMessage("请填写 API Key 后再保存", false)
                    return@launch
                }
                settings.saveAi(d.baseUrl, d.model, key, d.authHeaderName, d.authPrefix, d.visionModel, d.allowImageAnalysis, d.exactEndpoint)
                draft.value = d.copy(apiKey = "", hasStoredKey = true, loaded = true, touched = false)
                setMessage("配置已安全保存在本机", true)
            } catch (error: Exception) {
                // KeyVault 加密或 DataStore 写入失败会抛异常，不接住就会终止进程。
                setMessage("保存失败：${error.message ?: "请稍后重试"}", false)
            } finally { saving.value = false }
        }
    }

    fun testConnection() {
        val d = draft.value
        if (d.baseUrl.isBlank() || d.model.isBlank()) return setMessage("请先填写服务地址与模型", false)
        viewModelScope.launch {
            testing.value = true
            try {
                val stored = settings.currentAiConfig()
                val config = AiConfig(
                    baseUrl = d.baseUrl,
                    apiKey = d.apiKey.ifBlank { stored.apiKey },
                    model = d.model,
                    visionModel = d.visionModel,
                    allowImageAnalysis = d.allowImageAnalysis,
                    authHeaderName = d.authHeaderName,
                    authPrefix = d.authPrefix,
                    exactEndpoint = d.exactEndpoint,
                )
                when (val result = AiClient.complete(config, "你是连通性测试器", "收到请只回复：正常")) {
                    is AiClient.Result.Ok -> setMessage("连接正常", true)
                    is AiClient.Result.Fail -> setMessage("连接失败：${result.message}", false)
                }
            } finally { testing.value = false }
        }
    }

    fun setTheme(mode: String) { viewModelScope.launch { settings.saveThemeMode(mode) } }
    fun setReminder(enabled: Boolean, hour: Int, minute: Int) { viewModelScope.launch { settings.saveReminder(enabled, hour, minute) } }
    fun saveReminderSound(mode: String) { viewModelScope.launch { settings.saveReminderSound(mode) } }

    fun exportBackup(uri: Uri) = runDataTask("备份已导出；文件未加密，请妥善保管") { BackupManager.exportBackup(app, app.database, uri) }
    fun exportMarkdown(uri: Uri) = runDataTask("Markdown 已导出") { BackupManager.exportMarkdown(app, app.database, uri) }
    fun restore(uri: Uri, replace: Boolean) = runDataTask("") {
        val result = BackupManager.restore(app, app.database, uri, replace)
        app.database.todoDao().allOnce().filter { !it.done && it.reminderAt != null }.forEach { TodoReminderWorker.schedule(app, it) }
        dataMessage.value = buildString {
            append("恢复完成：${result.entries} 条记忆、${result.todos} 个行动、${result.images} 张图片")
            // 有图片没能恢复时必须说清楚，否则用户会以为照片都回来了。
            if (result.skippedImages > 0) append("；另有 ${result.skippedImages} 张图片在备份中缺失，未能恢复")
        }
    }

    private fun runDataTask(success: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            dataBusy.value = true
            dataMessage.value = ""
            try {
                block()
                if (success.isNotBlank()) dataMessage.value = success
            } catch (error: Exception) {
                dataMessage.value = "操作失败：${error.message ?: "文件不可用"}"
            } finally { dataBusy.value = false }
        }
    }

    private fun setMessage(value: String, ok: Boolean) { message.value = value to ok }
}
