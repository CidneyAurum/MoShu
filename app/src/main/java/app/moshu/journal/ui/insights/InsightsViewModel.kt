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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
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
    val activeDays: Int = 0,
    val streak: Int = 0,
    val categoryCounts: List<Int> = listOf(0, 0, 0),
    /** 本月每天的记录数（下标 0 = 1 号），用于活跃热力格 */
    val activityCounts: List<Int> = emptyList(),
    /** 本月 1 号对应的星期偏移（周一 = 0） */
    val firstDayOffset: Int = 0,
    val messages: List<ChatMessage> = emptyList(),
    val asking: Boolean = false,
    val review: AiReviewEntity? = null,
    val generatingReview: Boolean = false,
    /** 月回顾的即时反馈：空月份、未配置 AI 或生成失败时给出原因 */
    val reviewMessage: String = "",
    /** 首次查询返回前为 true，避免冷启动就把统计图表画成一片 0。 */
    val loading: Boolean = true,
)

@OptIn(ExperimentalCoroutinesApi::class)
class InsightsViewModel : ViewModel() {
    private val app = MoShuApp.instance
    private val dao = app.database.entryDao()
    private val monthOffset = MutableStateFlow(0)
    private val messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    private val asking = MutableStateFlow(false)
    private val generatingReview = MutableStateFlow(false)
    private val reviewMessage = MutableStateFlow("")
    private val loaded = MutableStateFlow(false)
    private var nextMessageId = 0L

    private fun month(offset: Int): YearMonth = YearMonth.now().minusMonths(offset.toLong())
    private fun periodKey(offset: Int): String = "month-${month(offset)}"

    private data class Stats(
        val label: String,
        val points: List<MoodPoint>,
        val entries: List<EntryEntity>,
        val avg: Double?,
        val streak: Int,
        val categories: List<Int>,
        val firstWeekday: Int,
    )

    private data class Conversation(
        val messages: List<ChatMessage>,
        val asking: Boolean,
        val generatingReview: Boolean,
        val reviewMessage: String,
        val loading: Boolean,
    )

    /**
     * 只查当前月份的条目，而不是整本日记再过滤：原先每次写入都会把全部行读出来
     * 并在主线程上重新分组统计，条目一多，每次保存都能看到卡顿。
     */
    private val stats = monthOffset.flatMapLatest { offset ->
        val ym = month(offset)
        val zone = ZoneId.systemDefault()
        val start = ym.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val end = ym.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        dao.observeBetween(start, end)
            .onEach { loaded.value = true }
            .map { entries -> buildStats(ym, entries) }
    }.flowOn(Dispatchers.Default)

    private fun buildStats(ym: YearMonth, entries: List<EntryEntity>): Stats {
        val byDay = entries.groupBy { dayOfMonth(it.createdAt) }
        val points = (1..ym.lengthOfMonth()).map { day ->
            val moods = byDay[day].orEmpty().mapNotNull { MOOD_SCORES[it.mood] }
            MoodPoint(day, moods.takeIf { it.isNotEmpty() }?.average(), byDay[day].orEmpty().size)
        }
        val scored = points.mapNotNull { it.score }
        // 连续天数按所看月份计算，锚点取「月末与今天中较早的一个」，
        // 否则卡片里会混进与本月无关的全局数字。
        val anchor = minOf(ym.atEndOfMonth(), LocalDate.now())
        return Stats(
            label = "${ym.year}年${ym.monthValue}月",
            points = points,
            entries = entries,
            avg = scored.takeIf { it.isNotEmpty() }?.average(),
            streak = calculateStreak(entries, anchor),
            categories = Category.NAMES.indices.map { category -> entries.count { it.categoryId == category } },
            firstWeekday = ym.atDay(1).dayOfWeek.value - 1,
        )
    }

    private val cachedReview = monthOffset.flatMapLatest { app.database.aiReviewDao().observe(periodKey(it)) }

    val uiState: StateFlow<InsightsUiState> = combine(
        stats,
        monthOffset,
        combine(messages, asking, generatingReview, reviewMessage, loaded) { msgs, busy, reviewBusy, note, isLoaded ->
            Conversation(msgs, busy, reviewBusy, note, !isLoaded)
        },
        cachedReview,
    ) { stat, offset, conversation, review ->
        InsightsUiState(
            monthOffset = offset,
            monthLabel = stat.label,
            points = stat.points,
            entryCount = stat.entries.size,
            avgMood = stat.avg,
            activeDays = stat.points.count { it.count > 0 },
            streak = stat.streak,
            categoryCounts = stat.categories,
            activityCounts = stat.points.map { it.count },
            firstDayOffset = stat.firstWeekday,
            messages = conversation.messages,
            asking = conversation.asking,
            review = review,
            generatingReview = conversation.generatingReview,
            reviewMessage = conversation.reviewMessage,
            loading = conversation.loading,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), InsightsUiState())

    fun prevMonth() { if (monthOffset.value < 24) monthOffset.value += 1 }
    fun nextMonth() { if (monthOffset.value > 0) monthOffset.value -= 1 }

    fun ask(question: String) {
        val value = question.trim()
        if (value.isEmpty() || asking.value) return
        viewModelScope.launch {
            messages.value += ChatMessage("user", value, id = nextMessageId++)
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
            reviewMessage.value = ""
            try {
                val offset = monthOffset.value
                val ym = month(offset)
                val zone = ZoneId.systemDefault()
                val entries = dao.between(
                    ym.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli(),
                    ym.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli(),
                )
                if (entries.isEmpty()) {
                    reviewMessage.value = "${ym.monthValue}月还没有记录，先记几句再来生成回顾。"
                    return@launch
                }
                val config = app.settings.currentAiConfig()
                if (!config.valid) {
                    reviewMessage.value = "还没配置 AI 服务，到设置页填写接口地址和 Key 后即可生成回顾。"
                    return@launch
                }
                // 只取最近 80 条会悄悄丢掉更早的记录：先说明用了多少条，避免用户
                // 以为整月都被读过。超出时在结果下方给一句提示。
                val used = entries.take(80)
                val corpus = used.joinToString("\n") { "- ${it.content.take(180)}" }.take(12_000)
                val system = "你是墨枢的月度回顾助手。仅根据记录，写一份温和、具体、不说教的中文回顾。包含：本月主线、值得肯定、需要留意、下月一个轻量建议。总计不超过350字。"
                when (val result = AiClient.complete(config, system, corpus, temperature = 0.5)) {
                    is AiClient.Result.Ok -> {
                        app.database.aiReviewDao().upsert(
                            AiReviewEntity(periodKey(offset), "month", result.text, JSONArray(entries.map { it.uid }).toString())
                        )
                        if (entries.size > used.size) {
                            reviewMessage.value = "本月记录较多，回顾基于最近 ${used.size} 条生成。"
                        }
                    }
                    is AiClient.Result.Fail -> reviewMessage.value = "生成失败：${result.message}"
                }
            } finally {
                generatingReview.value = false
            }
        }
    }

    private fun appendAssistant(text: String, sources: List<Long> = emptyList()) {
        messages.value += ChatMessage("assistant", text.trim(), sources, id = nextMessageId++)
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
