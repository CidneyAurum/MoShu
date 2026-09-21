package app.moshu.journal.reminder

import android.content.Context
import android.content.SharedPreferences
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.moshu.journal.MoShuApp
import app.moshu.journal.ai.AiClient
import app.moshu.journal.ai.Enricher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.random.Random

/**
 * M2：到点检查今天是否已有记录，一条都没有才提醒（未写补催语义）。
 * M3：优先用 AI 基于近 7 天日志生成「钩子」文案，失败/未配置则回退静态文案。
 */
class ReminderWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as MoShuApp
        if (!app.settings.reminderEnabled.first()) return Result.success()
        val (hour, minute) = app.settings.reminderTime.first()

        try {
            val now = System.currentTimeMillis()
            val todayStart = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis

            // 今天已经写过 → 不打扰
            if (app.database.entryDao().since(todayStart).isEmpty()) {
                Notifications.showReminder(applicationContext, buildHookText(app, now))
            }
        } finally {
            // AlarmManager 是一次性任务，每次执行后都续排下一天。
            ReminderScheduler.schedule(applicationContext, hour, minute)
        }

        return Result.success()
    }

    private suspend fun buildHookText(app: MoShuApp, now: Long): String {
        val fallback = FALLBACKS.random()
        val prefs = applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val dateKey = dayKey(now)

        // 同一天只生成一次：闹钟可能被系统重复触发，重复调用既费钱又可能换出另一句话，
        // 用户一天内会看到两条语气不同的提醒。
        prefs.getString(hookKey(dateKey), null)?.takeIf { isValidHook(it) }?.let { return it }

        val weekAgo = now - 7L * 24 * 60 * 60 * 1000
        val recent = app.database.entryDao().since(weekAgo).take(HOOK_CONTEXT_LIMIT)
        if (recent.isEmpty()) return fallback

        val config = app.settings.currentAiConfig()
        if (!config.valid) return fallback

        // 只发概括与标签：正文前 80 字里往往没有钩子所需的「具体事物」，模型只能凭空编。
        val history = hookHistory(prefs)
        val corpus = buildString {
            append(
                recent.joinToString(separator = "\n") { entry ->
                    val tags = Enricher.tagsOf(entry.tagsJson)
                    val head = entry.summary.ifBlank { entry.content.take(30) }
                    if (tags.isEmpty()) "- $head" else "- $head（${tags.joinToString("、")}）"
                }
            )
            if (history.isNotEmpty()) {
                append("\n\n不要与以下历史提问重复：\n")
                append(history.joinToString(separator = "\n") { "- $it" })
            }
        }
        val result = withTimeoutOrNull(12_000) {
            // 30 字受约束输出用 0.9 的温度只会最大化方差，也更难满足温柔、具体的要求。
            AiClient.complete(config, HOOK_SYSTEM_PROMPT, corpus, temperature = 0.6)
        }
        val hook = (result as? AiClient.Result.Ok)?.let { ok ->
            // 提醒文案同样是一次计费调用，不计入用量会让设置页的统计偏低。
            runCatching { app.settings.recordAiUsage(ok.promptTokens, ok.completionTokens) }
            ok.text
        }
            ?.let { sanitizeHook(it) }
            ?.takeIf { isValidHook(it) }
        if (hook == null) return fallback

        rememberHook(prefs, dateKey, hook)
        return hook.take(MAX_HOOK_CHARS)
    }

    companion object {
        private const val PREFS = "moshu_reminder_hooks"
        private const val KEY_HISTORY = "hook_history"
        private const val HOOK_CONTEXT_LIMIT = 12
        private const val HISTORY_LIMIT = 7
        private const val MIN_HOOK_CHARS = 8
        private const val MAX_HOOK_CHARS = 30

        private val HOOK_SYSTEM_PROMPT = """
你是日记助手「墨枢」。根据用户近期的日志摘录，生成一句温柔、具体的中文提问来勾起他今天写日记的欲望。
要求：只输出这一句话；不超过30字；要引用近期日志里的具体事物（如某件事、某个计划、某个情绪）；不要引号。
""".trim()

        private val FALLBACKS = listOf(
            "今天还没留下一笔，想到什么了？",
            "睡前一分钟，随手记一句今天的自己吧。",
            "此刻的心情，值得留个底。",
        )

        /** 只去掉包裹的引号，不提前截断——过长本身就是不合格，应当回退静态文案。 */
        fun sanitizeHook(text: String): String =
            text.trim().trim('"', '\'', '「', '」', '“', '”').trim()

        /** 约束之外的输出一律判废：太短没有信息、太长会被通知截断、没有问句就不像提问。 */
        fun isValidHook(text: String): Boolean =
            text.length in MIN_HOOK_CHARS..MAX_HOOK_CHARS && text.contains('？')

        private fun hookKey(dateKey: String): String = "hook_$dateKey"

        private fun dayKey(now: Long): String =
            SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(now))

        private fun hookHistory(prefs: SharedPreferences): List<String> = runCatching {
            val array = JSONArray(prefs.getString(KEY_HISTORY, "[]"))
            buildList {
                for (index in 0 until array.length()) {
                    array.optString(index, "").takeIf { it.isNotBlank() }?.let { add(it) }
                }
            }
        }.getOrDefault(emptyList())

        private fun rememberHook(prefs: SharedPreferences, dateKey: String, hook: String) {
            val updated = (hookHistory(prefs) + hook).takeLast(HISTORY_LIMIT)
            prefs.edit()
                .putString(hookKey(dateKey), hook)
                .putString(KEY_HISTORY, JSONArray(updated).toString())
                .apply()
        }
    }
}