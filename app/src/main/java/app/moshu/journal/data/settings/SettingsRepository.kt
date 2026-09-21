package app.moshu.journal.data.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.moshu.journal.BuildConfig
import app.moshu.journal.ai.AiConfig
import app.moshu.journal.data.security.KeyVault
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "moshu_settings")

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
    }

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
