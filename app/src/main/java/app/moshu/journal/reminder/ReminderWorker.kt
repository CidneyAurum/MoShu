package app.moshu.journal.reminder

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.moshu.journal.MoShuApp
import app.moshu.journal.ai.AiClient
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Calendar
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
        val weekAgo = now - 7L * 24 * 60 * 60 * 1000
        val recent = app.database.entryDao().since(weekAgo).take(30)
        if (recent.isEmpty()) return fallback

        val config = app.settings.currentAiConfig()
        if (!config.valid) return fallback

        val corpus = recent.joinToString(separator = "\n") { "- ${it.content.take(80)}" }.take(2500)
        val result = withTimeoutOrNull(12_000) {
            AiClient.complete(config, HOOK_SYSTEM_PROMPT, corpus, temperature = 0.9)
        }
        return when (result) {
            is AiClient.Result.Ok -> result.text.trim().removeSurrounding("\"").take(60)
                .ifBlank { fallback }
            else -> fallback
        }
    }

    private companion object {
        val HOOK_SYSTEM_PROMPT = """
你是日记助手「墨枢」。根据用户近期的日志摘录，生成一句温柔、具体的中文提问来勾起他今天写日记的欲望。
要求：只输出这一句话；不超过30字；要引用近期日志里的具体事物（如某件事、某个计划、某个情绪）；不要引号。
""".trim()

        val FALLBACKS = listOf(
            "今天还没留下一笔，想到什么了？",
            "睡前一分钟，随手记一句今天的自己吧。",
            "此刻的心情，值得留个底。",
        )
    }
}
