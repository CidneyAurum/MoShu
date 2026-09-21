package app.moshu.journal.data.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// 必须用独立的文件名：同一个 Context 上同名 preferencesDataStore 会在运行期直接抛异常，
// 而草稿写入频繁，混进 moshu_settings 会让设置页的 Flow 被反复唤醒。
private val Context.draftDataStore by preferencesDataStore(name = "moshu_draft")

/**
 * 「今天」输入框的草稿。切 tab 或进程被杀后仍能恢复，避免用户刚打的字凭空消失。
 * 空草稿会删除键而不是存空串，避免 DataStore 里留下无意义条目。
 */
class DraftStore(context: Context) {
    private val appContext = context.applicationContext
    private val key = stringPreferencesKey("composer_draft")

    val composerDraft: Flow<String> = appContext.draftDataStore.data.map { it[key].orEmpty() }

    suspend fun saveComposerDraft(value: String) {
        appContext.draftDataStore.edit { prefs ->
            if (value.isBlank()) prefs.remove(key) else prefs[key] = value
        }
    }
}