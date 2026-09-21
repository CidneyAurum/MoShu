package app.moshu.journal.ui.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.moshu.journal.MoShuApp
import app.moshu.journal.ai.AiClient
import app.moshu.journal.ai.AiConfig
import app.moshu.journal.ai.Enricher
import app.moshu.journal.data.backup.BackupManager
import app.moshu.journal.data.settings.AiUsage
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
    /** 模板自带说明，显示在服务地址下方。 */
    val hint: String = "",
    /** 已发现/缓存的模型 id，供点选。 */
    val discoveredModels: List<String> = emptyList(),
    val loadingModels: Boolean = false,
    /** 密钥为空时的就地校验提示，显示在 API Key 输入框下方。 */
    val apiKeyError: String = "",
    /** 服务地址主机名变了：已保存的密钥未必适用于新服务商。 */
    val hostChanged: Boolean = false,
    val usage: AiUsage = AiUsage(),
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
        /** 模板说明 */
        val hint: String = "",
        /** 已保存配置的主机名，用于判断服务地址是否被改到别处 */
        val savedHost: String = "",
        val discoveredModels: List<String> = emptyList(),
        val loadingModels: Boolean = false,
        val apiKeyError: String = "",
    )

    private data class Core(val draft: Draft, val saving: Boolean, val testing: Boolean, val message: Pair<String, Boolean>)
    private data class Peripheral(val enabled: Boolean, val hour: Int, val minute: Int, val sound: String, val theme: String)
    private data class DataState(val busy: Boolean, val message: String)

    private val core = combine(draft, saving, testing, message) { d, s, t, m -> Core(d, s, t, m) }
    private val peripheral = combine(settings.reminderEnabled, settings.reminderTime, settings.reminderSound, settings.themeMode) { enabled, time, sound, theme ->
        Peripheral(enabled, time.first, time.second, sound, theme)
    }
    private val dataState = combine(dataBusy, dataMessage) { busy, note -> DataState(busy, note) }

    val uiState: StateFlow<SettingsUiState> = combine(core, peripheral, dataState, settings.keyUnreadable, settings.aiUsage) { c, p, data, unreadable, usage ->
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
            hint = c.draft.hint,
            discoveredModels = c.draft.discoveredModels,
            loadingModels = c.draft.loadingModels,
            apiKeyError = c.draft.apiKeyError,
            // 主机名变了才提醒：同一服务商换路径（如补上 /v1）不该要求重新确认密钥。
            hostChanged = c.draft.hasStoredKey && hostOf(c.draft.baseUrl) != hostOf(c.draft.savedHost),
            usage = usage,
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
                        savedHost = config.baseUrl,
                    )
                }
                loadCachedModels(config.baseUrl)
            }
        }
    }

    /** 已按 URL 缓存的模型列表，避免每次回到设置页都重新请求。 */
    private var modelsLoadedFor: String? = null

    private fun loadCachedModels(baseUrl: String) {
        val key = baseUrl.trim().trimEnd('/')
        if (key == modelsLoadedFor) return
        modelsLoadedFor = key
        viewModelScope.launch {
            val cached = if (key.isBlank()) emptyList() else settings.discoveredModels(baseUrl)
            draft.value = draft.value.copy(discoveredModels = cached)
        }
    }

    fun applyTemplate(template: AiConfig.Template) {
        draft.value = draft.value.copy(
            baseUrl = template.url,
            model = template.model,
            // 模板自带视觉模型时一并填好，用户之后打开图片理解就不用手填。
            visionModel = template.visionModel.ifBlank { draft.value.visionModel },
            hint = template.hint,
            exactEndpoint = false,
            touched = true,
        )
        loadCachedModels(template.url)
    }

    fun onBaseUrlChange(value: String) {
        draft.value = draft.value.copy(baseUrl = value, touched = true)
        loadCachedModels(value)
    }

    fun onModelChange(value: String) { draft.value = draft.value.copy(model = value, touched = true) }
    fun onVisionModelChange(value: String) { draft.value = draft.value.copy(visionModel = value, touched = true) }
    fun onAllowImagesChange(value: Boolean) { draft.value = draft.value.copy(allowImageAnalysis = value, touched = true) }
    fun onApiKeyChange(value: String) { draft.value = draft.value.copy(apiKey = value.trim(), apiKeyError = "", touched = true) }
    fun onAuthHeaderChange(value: String) { draft.value = draft.value.copy(authHeaderName = value.trim(), touched = true) }

    /** 前缀必须原样保留空格：默认的 "Bearer " 一旦被 trim 成 "Bearer"，就会发出 "Bearersk-…"。 */
    fun onAuthPrefixChange(value: String) { draft.value = draft.value.copy(authPrefix = value, touched = true) }
    fun onExactEndpointChange(value: Boolean) { draft.value = draft.value.copy(exactEndpoint = value, touched = true) }

    fun save() {
        val d = draft.value
        if (d.baseUrl.isBlank() || d.model.isBlank()) return setMessage("Base URL 与文本模型不能为空", false)
        if (d.allowImageAnalysis && d.visionModel.isBlank()) return setMessage("开启图片理解前，请填写视觉模型", false)
        viewModelScope.launch {
            saving.value = true
            try {
                val key = d.apiKey.ifBlank { settings.currentAiConfig().apiKey }
                // 密钥为空时把错误就地放在输入框下方并聚焦，而不是丢一条容易被忽略的全局提示。
                // 换锁屏或恢复备份后密钥解不开时，用户一眼就能看到该在哪里补。
                if (key.isBlank()) {
                    draft.value = draft.value.copy(apiKeyError = "请填写 API Key 后再保存")
                    return@launch
                }
                settings.saveAi(d.baseUrl, d.model, key, d.authHeaderName, d.authPrefix, d.visionModel, d.allowImageAnalysis, d.exactEndpoint)
                draft.value = d.copy(apiKey = "", hasStoredKey = true, loaded = true, touched = false, savedHost = d.baseUrl, apiKeyError = "")
                setMessage("配置已安全保存在本机", true)
            } catch (error: Exception) {
                // KeyVault 加密或 DataStore 写入失败会抛异常，不接住就会终止进程。
                setMessage("保存失败：${error.message ?: "请稍后重试"}", false)
            } finally { saving.value = false }
        }
    }

    /** 轮换/撤销密钥。留空输入框的语义是「保持原密钥」，因此清除必须走这条显式路径。 */
    fun clearAiKey() {
        viewModelScope.launch {
            try {
                settings.clearAiKey()
                draft.value = draft.value.copy(apiKey = "", hasStoredKey = false, apiKeyError = "")
                setMessage("已清除本机保存的密钥", true)
            } catch (error: Exception) {
                setMessage("清除失败：${error.message ?: "请稍后重试"}", false)
            }
        }
    }

    /** 读取服务商的模型列表，避免手填模型名拼错；失败时保留手填能力。 */
    fun fetchModels() {
        val d = draft.value
        if (d.baseUrl.isBlank()) return setMessage("请先填写服务地址", false)
        viewModelScope.launch {
            draft.value = draft.value.copy(loadingModels = true)
            try {
                val stored = settings.currentAiConfig()
                val config = AiConfig(
                    baseUrl = d.baseUrl,
                    apiKey = d.apiKey.ifBlank { stored.apiKey },
                    model = d.model.ifBlank { stored.model },
                    visionModel = d.visionModel,
                    allowImageAnalysis = d.allowImageAnalysis,
                    authHeaderName = d.authHeaderName,
                    authPrefix = d.authPrefix,
                    exactEndpoint = d.exactEndpoint,
                )
                when (val result = AiClient.listModels(config)) {
                    is AiClient.ModelsResult.Ok -> {
                        modelsLoadedFor = d.baseUrl.trim().trimEnd('/')
                        draft.value = draft.value.copy(discoveredModels = result.models)
                        settings.saveDiscoveredModels(d.baseUrl, result.models)
                        setMessage(
                            if (result.models.isEmpty()) "服务商没有返回可用模型，仍可手动填写" else "已读取 ${result.models.size} 个模型",
                            result.models.isNotEmpty(),
                        )
                    }
                    is AiClient.ModelsResult.Fail -> setMessage("读取失败：${result.message}；仍可手动填写", false)
                }
            } finally {
                draft.value = draft.value.copy(loadingModels = false)
            }
        }
    }

    fun selectModel(id: String) { draft.value = draft.value.copy(model = id, touched = true) }

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
                val lines = mutableListOf<String>()
                var ok = true

                // 1) 文本连通性：不能只看 HTTP 200，还要确认模型确实按指令回复，
                //    并报出真实模型名与耗时，否则网关拒答或换模型都发现不了。
                val textStart = System.currentTimeMillis()
                when (val result = AiClient.complete(config, "你是连通性测试器", "收到请只回复：正常")) {
                    is AiClient.Result.Ok -> {
                        val elapsed = System.currentTimeMillis() - textStart
                        if (result.text.contains("正常")) {
                            lines += buildString {
                                append("连接正常 · ${result.model.ifBlank { config.model }} · ${elapsed}ms")
                                if (result.promptTokens > 0 || result.completionTokens > 0) {
                                    append(" · 输入 ${result.promptTokens} / 输出 ${result.completionTokens} tokens")
                                }
                            }
                        } else {
                            ok = false
                            lines += "连接异常：模型没有按预期回复（实际返回「${result.text.take(40)}」），请确认模型名与网关设置"
                        }
                    }
                    is AiClient.Result.Fail -> {
                        ok = false
                        lines += "连接失败：${result.message}"
                    }
                }

                // 2) 视觉链路：只有真发一张图才能证明图片理解可用。
                if (d.allowImageAnalysis) {
                    if (d.visionModel.isBlank()) {
                        ok = false
                        lines += "视觉模型未填写，图片理解无法工作"
                    } else {
                        val visionStart = System.currentTimeMillis()
                        val visionResult = AiClient.complete(
                            config.copy(allowImageAnalysis = true),
                            "你是连通性测试器",
                            "这张图是什么颜色？只回答颜色",
                            images = listOf(AiClient.ImageInput("image/jpeg", TINY_JPEG_BASE64)),
                        )
                        val elapsed = System.currentTimeMillis() - visionStart
                        when (visionResult) {
                            is AiClient.Result.Ok -> lines += "视觉模型正常 · ${visionResult.model.ifBlank { d.visionModel }} · ${elapsed}ms"
                            is AiClient.Result.Fail -> {
                                ok = false
                                lines += "视觉模型失败：${visionResult.message}"
                            }
                        }
                    }
                }

                // 3) 整理链路：走真实的 Enricher 路径跑一次 JSON 往返，确认返回值能被解析。
                //    单独发提示词只能证明「能回复」，走 enrich 才能同时验证解析与兜底修复。
                val sample = "今天下午和同事开了会，明天要把方案初稿发给张老师。"
                val (enrichResult, enrichment) = Enricher.enrich(config, sample)
                lines += when {
                    enrichment != null -> "整理链路正常"
                    enrichResult is AiClient.Result.Fail -> "整理链路失败：${enrichResult.message}"
                    else -> "整理返回无法解析"
                }

                setMessage(lines.joinToString("\n"), ok)
            } finally { testing.value = false }
        }
    }

    /** 取主机名用于展示与「地址是否变更」判断；没写协议的地址也尽量解析。 */
    private fun hostOf(url: String): String {
        val value = url.trim()
        if (value.isBlank()) return ""
        return runCatching { java.net.URI(if (value.contains("://")) value else "https://$value").host }
            .getOrNull()?.takeIf { it.isNotBlank() }
            ?: value.trimEnd('/').lowercase()
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

/** 1×1 白色 JPEG：用于验证视觉链路，体积最小且必定是合法图片。 */
private const val TINY_JPEG_BASE64 =
    "/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRofHh0aHBwgJC4nICIsIxwcKDcpLDAxNDQ0Hyc5PTgyPC4zNDL/2wBDAQkJCQwLDBgNDRgyIRwhMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjL/wAARCAABAAEDASIAAhEBAxEB/8QAHwAAAQUBAQEBAQEAAAAAAAAAAAECAwQFBgcICQoL/8QAtRAAAgEDAwIEAwUFBAQAAAF9AQIDAAQRBRIhMUEGE1FhByJxFDKBkaEII0KxwRVS0fAkM2JyggkKFhcYGRolJicoKSo0NTY3ODk6Q0RFRkdISUpTVFVWV1hZWmNkZWZnaGlqc3R1dnd4eXqDhIWGh4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXGx8jJytLT1NXW19jZ2uHi4+Tl5ufo6erx8vP09fb3+Pn6/8QAHwEAAwEBAQEBAQEBAQAAAAAAAAECAwQFBgcICQoL/8QAtREAAgECBAQDBAcFBAQAAQJ3AAECAxEEBSExBhJBUQdhcRMiMoEIFEKRobHBCSMzUvAVYnLRChYkNOEl8RcYGRomJygpKjU2Nzg5OkNERUZHSElKU1RVVldYWVpjZGVmZ2hpanN0dXZ3eHl6goOEhYaHiImKkpOUlZaXmJmaoqOkpaanqKmqsrO0tba3uLm6wsPExcbHyMnK0tPU1dbX2Nna4uPk5ebn6Onq8vP09fb3+Pn6/9oADAMBAAIRAxEAPwD3+iiigD//2Q=="
