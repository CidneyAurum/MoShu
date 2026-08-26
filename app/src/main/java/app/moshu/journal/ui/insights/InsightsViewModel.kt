package app.moshu.journal.ui.insights

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.moshu.journal.MoShuApp
import app.moshu.journal.ai.AiClient
import app.moshu.journal.ai.AskEngine
import app.moshu.journal.ai.AskEngine.ChatMessage
import app.moshu.journal.data.db.AiReviewEntity
import app.moshu.journal.data.db.Category
import app.moshu.journal.data.db.EntryEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

data class MoodPoint(val day: Int, val score: Double?, val count: Int)

data class InsightsUiState(
    val monthOffset: Int = 0,
    val monthLabel: String = "",
    val points: List<MoodPoint> = emptyList(),
    val entryCount: Int = 0,
    val avgMood: Double? = null,
    val lowDays: Int = 0,
    val activeDays: Int = 0,
    val streak: Int = 0,
    val categoryCounts: List<Int> = listOf(0, 0, 0),
    val messages: List<ChatMessage> = emptyList(),
    val asking: Boolean = false,
    val review: AiReviewEntity? = null,
    val generatingReview: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
class InsightsViewModel : ViewModel() {
    private val app = MoShuApp.instance
    private val dao = app.database.entryDao()
    private val monthOffset = MutableStateFlow(0)
    private val messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    private val asking = MutableStateFlow(false)
    private val generatingReview = MutableStateFlow(false)

    private fun month(offset: Int): YearMonth = YearMonth.now().minusMonths(offset.toLong())
    private fun periodKey(offset: Int): String = "month-${month(offset)}"

    private data class Stats(
        val label: String,
        val points: List<MoodPoint>,
        val entries: List<EntryEntity>,
        val avg: Double?,
        val lowDays: Int,
        val streak: Int,
        val categories: List<Int>,
    )

    private val stats = combine(dao.observeAll(), monthOffset) { all, offset ->
        val ym = month(offset)
        val zone = ZoneId.systemDefault()
        val start = ym.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val end = ym.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val entries = all.filter { it.createdAt in start until end }
        val byDay = entries.groupBy { dayOfMonth(it.createdAt) }
        val points = (1..ym.lengthOfMonth()).map { day ->
            val moods = byDay[day].orEmpty().mapNotNull { MOOD_SCORES[it.mood] }
            MoodPoint(day, moods.takeIf { it.isNotEmpty() }?.average(), byDay[day].orEmpty().size)
        }
        val scored = points.mapNotNull { it.score }
        Stats(
            label = "${ym.year}年${ym.monthValue}月",
            points = points,
            entries = entries,
            avg = scored.takeIf { it.isNotEmpty() }?.average(),
            lowDays = points.count { it.score != null && it.score <= 2.0 },
            streak = calculateStreak(all),
            categories = Category.NAMES.indices.map { category -> entries.count { it.categoryId == category } },
        )
    }

    private val cachedReview = monthOffset.flatMapLatest { app.database.aiReviewDao().observe(periodKey(it)) }

    val uiState: StateFlow<InsightsUiState> = combine(
        stats,
        monthOffset,
        combine(messages, asking, generatingReview) { msgs, busy, reviewBusy -> Triple(msgs, busy, reviewBusy) },
        cachedReview,
    ) { stat, offset, conversation, review ->
        InsightsUiState(
            monthOffset = offset,
            monthLabel = stat.label,
            points = stat.points,
            entryCount = stat.entries.size,
            avgMood = stat.avg,
            lowDays = stat.lowDays,
            activeDays = stat.points.count { it.count > 0 },
            streak = stat.streak,
            categoryCounts = stat.categories,
            messages = conversation.first,
            asking = conversation.second,
            review = review,
            generatingReview = conversation.third,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), InsightsUiState())

    fun prevMonth() { if (monthOffset.value < 24) monthOffset.value += 1 }
    fun nextMonth() { if (monthOffset.value > 0) monthOffset.value -= 1 }

    fun ask(question: String) {
        val value = question.trim()
        if (value.isEmpty() || asking.value) return
        viewModelScope.launch {
            messages.value += ChatMessage("user", value)
            asking.value = true
            try {
                val config = app.settings.currentAiConfig()
                if (!config.valid) return@launch appendAssistant("还没配置 AI 服务。你仍可查看所有本地统计，配置后再来问我。")
                val excerpts = AskEngine.retrieve(dao, value, System.currentTimeMillis())
                if (excerpts.isEmpty()) return@launch appendAssistant("暂时没有找到相关记忆。再记录一些，我会更了解你。")
                val prompt = AskEngine.buildUserPrompt(excerpts) + "\n\n【问题】$value"
                when (val result = AiClient.complete(config, AskEngine.SYSTEM_PROMPT, prompt, temperature = 0.5)) {
                    is AiClient.Result.Ok -> appendAssistant(result.text, excerpts.map { it.id })
                    is AiClient.Result.Fail -> appendAssistant("回答失败：${result.message}")
                }
            } finally {
                asking.value = false
            }
        }
    }

    fun generateMonthlyReview() {
        if (generatingReview.value) return
        viewModelScope.launch {
            generatingReview.value = true
            try {
                val offset = monthOffset.value
                val ym = month(offset)
                val zone = ZoneId.systemDefault()
                val entries = dao.between(
                    ym.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli(),
                    ym.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli(),
                )
                if (entries.isEmpty()) return@launch
                val config = app.settings.currentAiConfig()
                if (!config.valid) return@launch
                val corpus = entries.take(80).joinToString("\n") { "- ${it.content.take(180)}" }.take(12_000)
                val system = "你是墨枢的月度回顾助手。仅根据记录，写一份温和、具体、不说教的中文回顾。包含：本月主线、值得肯定、需要留意、下月一个轻量建议。总计不超过350字。"
                when (val result = AiClient.complete(config, system, corpus, temperature = 0.5)) {
                    is AiClient.Result.Ok -> app.database.aiReviewDao().upsert(
                        AiReviewEntity(periodKey(offset), "month", result.text, JSONArray(entries.map { it.uid }).toString())
                    )
                    is AiClient.Result.Fail -> appendAssistant("月度回顾生成失败：${result.message}")
                }
            } finally {
                generatingReview.value = false
            }
        }
    }

    private fun appendAssistant(text: String, sources: List<Long> = emptyList()) {
        messages.value += ChatMessage("assistant", text.trim(), sources)
    }

    companion object {
        val MOOD_SCORES = mapOf("great" to 5.0, "good" to 4.0, "neutral" to 3.0, "low" to 2.0, "bad" to 1.0)

        fun dayOfMonth(timestamp: Long): Int = Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).dayOfMonth

        fun calculateStreak(entries: List<EntryEntity>, today: LocalDate = LocalDate.now()): Int {
            val days = entries.map { Instant.ofEpochMilli(it.createdAt).atZone(ZoneId.systemDefault()).toLocalDate() }.toSet()
            var cursor = if (today in days) today else today.minusDays(1)
            var count = 0
            while (cursor in days) { count += 1; cursor = cursor.minusDays(1) }
            return count
        }
    }
}
