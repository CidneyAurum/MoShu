package app.moshu.journal.data.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.moshu.journal.BuildConfig
import app.moshu.journal.ai.AiConfig
import app.moshu.journal.data.security.KeyVault
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.dataStore by preferencesDataStore(name = "moshu_settings")

/** AI 用量累计。日粒度按设备本地日期记录，用于在设置页展示「本月约 X 次调用」。 */
data class AiUsage(
    val todayCalls: Int = 0,
    val todayTokens: Int = 0,
    val monthCalls: Int = 0,
    val monthTokens: Int = 0,
    val totalCalls: Int = 0,
    val totalTokens: Int = 0,
)

class SettingsRepository(context: Context) {

    private val appContext = context.applicationContext

    private object Keys {
        val BASE_URL = stringPreferencesKey("ai_base_url")
        val MODEL = stringPreferencesKey("ai_model")
        val VISION_MODEL = stringPreferencesKey("ai_vision_model")
        val ALLOW_IMAGE_AI = booleanPreferencesKey("ai_allow_images")
        val API_KEY_ENC = stringPreferencesKey("ai_key_encrypted")
        val AUTH_HEADER = stringPreferencesKey("ai_auth_header")
        val AUTH_PREFIX = stringPreferencesKey("ai_auth_prefix")
        val EXACT_ENDPOINT = booleanPreferencesKey("ai_exact_endpoint")
        val REMINDER_ENABLED = booleanPreferencesKey("reminder_enabled")
        val REMINDER_HOUR = intPreferencesKey("reminder_hour")
        val REMINDER_MINUTE = intPreferencesKey("reminder_minute")
        // ""=系统默认 "silent"=静音 其他=内容 Uri 字符串
        val REMINDER_SOUND = stringPreferencesKey("reminder_sound")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
        // 每天一行 "YYYY-MM-DD": [调用次数, token 数]
        val AI_USAGE_DAILY = stringPreferencesKey("ai_usage_daily")
        val AI_USAGE_TOTAL_CALLS = longPreferencesKey("ai_usage_total_calls")
        val AI_USAGE_TOTAL_TOKENS = longPreferencesKey("ai_usage_total_tokens")
        // 已发现的模型列表，按 baseUrl 存，避免每次进设置页都要重新拉取
        val AI_MODELS_BY_URL = stringPreferencesKey("ai_models_by_url")
    }

    /** 读取按 URL 缓存模型的归一化键，末尾斜杠不影响命中。 */
    private fun modelKey(baseUrl: String): String = baseUrl.trim().trimEnd('/')

    private val storedConfig: Flow<Pair<AiConfig, Boolean>> = appContext.dataStore.data.map { prefs ->
        val encrypted = prefs[Keys.API_KEY_ENC].orEmpty()
        val decrypted = if (encrypted.isBlank()) null else runCatching { KeyVault.decrypt(encrypted) }.getOrNull()
        AiConfig(
            baseUrl = prefs[Keys.BASE_URL].orEmpty(),
            model = prefs[Keys.MODEL].orEmpty(),
            visionModel = prefs[Keys.VISION_MODEL].orEmpty(),
            allowImageAnalysis = prefs[Keys.ALLOW_IMAGE_AI] ?: false,
            apiKey = decrypted.orEmpty(),
            authHeaderName = prefs[Keys.AUTH_HEADER] ?: "Authorization",
            authPrefix = prefs[Keys.AUTH_PREFIX] ?: "Bearer ",
            exactEndpoint = prefs[Keys.EXACT_ENDPOINT] ?: false,
        ) to (encrypted.isNotBlank() && decrypted == null)
    }

    val aiConfig: Flow<AiConfig> = storedConfig.map { it.first }

    /**
     * 密文在、但解不开：换了锁屏方式、清了 Keystore 或从备份恢复都会这样。
     * 这时不能只显示「尚未配置」，否则用户完全不知道密钥为什么失效了。
     */
    val keyUnreadable: Flow<Boolean> = storedConfig.map { it.second }

    /**
     * 实际生效的 AI 配置：用户显式保存的优先；
     * Debug 构建下若从未保存过，则回退到 local.properties 注入值，开箱即用。
     */
    suspend fun currentAiConfig(): AiConfig {
        val stored = aiConfig.first()
        if (stored.valid) return stored
        if (BuildConfig.DEBUG && BuildConfig.AUTO_AI_BASE_URL.isNotBlank() && BuildConfig.AUTO_AI_MODEL.isNotBlank()) {
            return AiConfig(
                baseUrl = BuildConfig.AUTO_AI_BASE_URL,
                model = BuildConfig.AUTO_AI_MODEL,
                apiKey = BuildConfig.AUTO_AI_KEY,
                visionModel = stored.visionModel,
                allowImageAnalysis = stored.allowImageAnalysis,
                authHeaderName = stored.authHeaderName,
                authPrefix = stored.authPrefix,
                exactEndpoint = stored.exactEndpoint,
            )
        }
        return stored
    }

    /** 返回加密后的密文（用于 UI 展示是否已设置） */
    suspend fun saveAi(
        baseUrl: String,
        model: String,
        apiKey: String,
        authHeaderName: String = "Authorization",
        authPrefix: String = "Bearer ",
        visionModel: String = "",
        allowImageAnalysis: Boolean = false,
        exactEndpoint: Boolean = false,
    ) {
        appContext.dataStore.edit { prefs ->
            prefs[Keys.BASE_URL] = baseUrl.trim()
            prefs[Keys.MODEL] = model.trim()
            prefs[Keys.VISION_MODEL] = visionModel.trim()
            prefs[Keys.ALLOW_IMAGE_AI] = allowImageAnalysis
            prefs[Keys.API_KEY_ENC] = if (apiKey.isBlank()) "" else KeyVault.encrypt(apiKey.trim())
            prefs[Keys.AUTH_HEADER] = authHeaderName.trim().ifBlank { "Authorization" }
            prefs[Keys.AUTH_PREFIX] = authPrefix
            prefs[Keys.EXACT_ENDPOINT] = exactEndpoint
        }
    }

    /**
     * 清除已保存的密钥。留空输入框表示「保持原密钥」，因此轮换/撤销必须走这条显式路径，
     * 否则用户永远删不掉本机上的旧密钥。
     */
    suspend fun clearAiKey() {
        appContext.dataStore.edit { it[Keys.API_KEY_ENC] = "" }
    }

    /**
     * 记一次真实的模型调用。只有真正发出请求才应调用，用于把「每条记录都会计费」
     * 这件事变成用户可见的数字。
     */
    suspend fun recordAiUsage(promptTokens: Int, completionTokens: Int) {
        val tokens = promptTokens.coerceAtLeast(0) + completionTokens.coerceAtLeast(0)
        val today = LocalDate.now().toString()
        appContext.dataStore.edit { prefs ->
            val daily = runCatching { JSONObject(prefs[Keys.AI_USAGE_DAILY].orEmpty().ifBlank { "{}" }) }
                .getOrDefault(JSONObject())
            val current = daily.optJSONArray(today)
            daily.put(today, JSONArray().put((current?.optInt(0) ?: 0) + 1).put((current?.optInt(1) ?: 0) + tokens))
            // 只保留最近 40 天：够算本月，也不会无限增长。
            val cutoff = LocalDate.now().minusDays(40).toString()
            daily.keys().asSequence().filter { it < cutoff }.toList().forEach { daily.remove(it) }
            prefs[Keys.AI_USAGE_DAILY] = daily.toString()
            prefs[Keys.AI_USAGE_TOTAL_CALLS] = (prefs[Keys.AI_USAGE_TOTAL_CALLS] ?: 0L) + 1
            prefs[Keys.AI_USAGE_TOTAL_TOKENS] = (prefs[Keys.AI_USAGE_TOTAL_TOKENS] ?: 0L) + tokens
        }
    }

    val aiUsage: Flow<AiUsage> = appContext.dataStore.data.map { prefs ->
        val daily = runCatching { JSONObject(prefs[Keys.AI_USAGE_DAILY].orEmpty().ifBlank { "{}" }) }
            .getOrDefault(JSONObject())
        val today = LocalDate.now().toString()
        val monthPrefix = today.substring(0, 7)
        var todayCalls = 0
        var todayTokens = 0
        var monthCalls = 0
        var monthTokens = 0
        daily.keys().forEach { day ->
            val row = daily.optJSONArray(day) ?: return@forEach
            val calls = row.optInt(0)
            val tokens = row.optInt(1)
            if (day == today) {
                todayCalls = calls
                todayTokens = tokens
            }
            if (day.startsWith(monthPrefix)) {
                monthCalls += calls
                monthTokens += tokens
            }
        }
        AiUsage(
            todayCalls = todayCalls,
            todayTokens = todayTokens,
            monthCalls = monthCalls,
            monthTokens = monthTokens,
            totalCalls = (prefs[Keys.AI_USAGE_TOTAL_CALLS] ?: 0L).toInt(),
            totalTokens = (prefs[Keys.AI_USAGE_TOTAL_TOKENS] ?: 0L).toInt(),
        )
    }

    /** 持久化某地址下发现的模型 id，让列表在页面跳转后依然可用。 */
    suspend fun saveDiscoveredModels(baseUrl: String, ids: List<String>) {
        val key = modelKey(baseUrl)
        if (key.isBlank()) return
        appContext.dataStore.edit { prefs ->
            val map = runCatching { JSONObject(prefs[Keys.AI_MODELS_BY_URL].orEmpty().ifBlank { "{}" }) }
                .getOrDefault(JSONObject())
            map.put(key, JSONArray(ids))
            // 上限 20 个地址，超出时丢掉最早的键，避免设置项无限膨胀。
            if (map.length() > 20) {
                map.keys().asSequence().drop(20).toList().forEach { map.remove(it) }
            }
            prefs[Keys.AI_MODELS_BY_URL] = map.toString()
        }
    }

    suspend fun discoveredModels(baseUrl: String): List<String> {
        val key = modelKey(baseUrl)
        if (key.isBlank()) return emptyList()
        val raw = appContext.dataStore.data.first()[Keys.AI_MODELS_BY_URL].orEmpty()
        val array = runCatching { JSONObject(raw.ifBlank { "{}" }).optJSONArray(key) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { array.optString(it).takeIf { id -> id.isNotBlank() } }
    }

    val reminderEnabled: Flow<Boolean> =
        appContext.dataStore.data.map { it[Keys.REMINDER_ENABLED] ?: false }

    val reminderTime: Flow<Pair<Int, Int>> =
        appContext.dataStore.data.map {
            (it[Keys.REMINDER_HOUR] ?: 21) to (it[Keys.REMINDER_MINUTE] ?: 30)
        }

    suspend fun saveReminder(enabled: Boolean, hour: Int, minute: Int) {
        appContext.dataStore.edit { prefs ->
            prefs[Keys.REMINDER_ENABLED] = enabled
            prefs[Keys.REMINDER_HOUR] = hour
            prefs[Keys.REMINDER_MINUTE] = minute
        }
    }

    val reminderSound: Flow<String> =
        appContext.dataStore.data.map { it[Keys.REMINDER_SOUND] ?: "" }

    suspend fun saveReminderSound(mode: String) {
        appContext.dataStore.edit { it[Keys.REMINDER_SOUND] = mode }
    }

    val themeMode: Flow<String> = appContext.dataStore.data.map { it[Keys.THEME_MODE] ?: "system" }

    suspend fun saveThemeMode(mode: String) {
        val safe = mode.takeIf { it in setOf("system", "light", "dark") } ?: "system"
        appContext.dataStore.edit { it[Keys.THEME_MODE] = safe }
    }

    val onboardingDone: Flow<Boolean> = appContext.dataStore.data.map { it[Keys.ONBOARDING_DONE] ?: false }

    suspend fun completeOnboarding() {
        appContext.dataStore.edit { it[Keys.ONBOARDING_DONE] = true }
    }
}
