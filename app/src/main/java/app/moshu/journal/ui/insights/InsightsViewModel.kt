package app.moshu.journal.ui.insights

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.moshu.journal.BuildConfig
import app.moshu.journal.MoShuApp
import app.moshu.journal.ai.AiClient
import app.moshu.journal.ai.AiConfig
import app.moshu.journal.ai.AskEngine
import app.moshu.journal.data.db.AiReviewEntity
import app.moshu.journal.data.db.Category
import app.moshu.journal.data.db.EntryEntity
import app.moshu.journal.data.db.TodoEntity
import app.moshu.journal.ui.components.parseTags
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import app.moshu.journal.data.MoodTagLink
import app.moshu.journal.data.WritingStats
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.Date
import java.util.Locale

data class MoodPoint(val day: Int, val score: Double?, val count: Int)

/** 对话气泡状态。PENDING 表示问题已发出但还没收尾，进程死亡或离开页面后会被降级为 INTERRUPTED。 */
enum class TurnStatus { NORMAL, PENDING, INTERRUPTED }

/**
 * 对话消息。不能复用 AskEngine.ChatMessage：取消/重试状态、被问的问题和「是否真实回答」
 * 都要跟着气泡一起持久化，否则离开页面重进就只剩一句无从操作的文本。
 */
data class ChatTurn(
    val id: Long,
    val role: String, // "user" | "assistant"
    val text: String,
    val sourceEntryIds: List<Long> = emptyList(),
    /** 生成这条气泡的提问。user 消息即自身文本，assistant 消息用于「重新生成」。 */
    val question: String = "",
    val status: TurnStatus = TurnStatus.NORMAL,
    /** true 表示这是模型的真实回答（可以谈引用来源），false 是配置/失败/取消这类系统提示。 */
    val isAnswer: Boolean = false,
)

/** 回顾区提示的严重程度：信息、警告、错误，决定颜色与是否给操作按钮。 */
enum class ReviewSeverity { INFO, WARN, ERROR }

/** 回顾提示附带的动作。 */
enum class ReviewAction { SETTINGS, REGENERATE }

data class ReviewNotice(val text: String, val severity: ReviewSeverity, val action: ReviewAction? = null)

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
    val messages: List<ChatTurn> = emptyList(),
    val asking: Boolean = false,
    /** 忙碌时用户再发问的提示（不丢弃输入，只说明原因）。 */
    val askHint: String = "",
    /** 从用户自己的数据推出的三个提问建议。 */
    val suggestions: List<String> = emptyList(),
    /** 当前是否已配置可用的 AI；未配置时首页给出「去配置」入口。 */
    val aiConfigured: Boolean = false,
    /** 当前生效的模型名，用于判断已存回顾是否来自旧模型。 */
    val currentModel: String = "",
    val review: AiReviewEntity? = null,
    val generatingReview: Boolean = false,
    /** 带严重程度的即时反馈（空月份、未配置、失败、记录已变化）。 */
    val reviewNotice: ReviewNotice? = null,
    /** 已存回顾的模型与当前配置不一致，提示可以重新生成。 */
    val reviewModelChanged: Boolean = false,
    /** 首次查询返回前为 true，避免冷启动就把统计图表画成一片 0。 */
    val loading: Boolean = true,
)

@OptIn(ExperimentalCoroutinesApi::class)
class InsightsViewModel : ViewModel() {
    private val app = MoShuApp.instance
    private val dao = app.database.entryDao()
    private val monthOffset = MutableStateFlow(0)
    private val messages = MutableStateFlow<List<ChatTurn>>(emptyList())
    private val asking = MutableStateFlow(false)
    private val askHint = MutableStateFlow("")
    private val generatingReview = MutableStateFlow(false)
    private val reviewNotice = MutableStateFlow<ReviewNotice?>(null)
    private val loaded = MutableStateFlow(false)
    private var nextMessageId = 0L

    /** 正在进行的提问任务。停止按钮通过它取消请求。 */
    private var askJob: Job? = null

    /** 上一轮命中的摘录。追问时沿用，保证回答里的 [n] 编号与上一轮一致。 */
    private var lastExcerpts: List<EntryEntity> = emptyList()

    private val chatFile = File(app.filesDir, "insights_chat.json")

    init {
        // 先恢复历史对话再开始回写，否则初始的空列表会把已存文件覆盖掉。
        viewModelScope.launch(Dispatchers.IO) {
            restoreMessages()
            messages.collect { persistMessages(it) }
        }
    }

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

    private data class Flags(
        val asking: Boolean,
        val askHint: String,
        val generatingReview: Boolean,
        val notice: ReviewNotice?,
        val loaded: Boolean,
    )

    private data class Conversation(val messages: List<ChatTurn>, val flags: Flags)

    /** 对话之外的额外输入：待办与 AI 配置（含 Debug 兜底模型）。 */
    private data class Extras(val todos: List<TodoEntity>, val model: String, val aiConfigured: Boolean)

    private val flags = combine(asking, askHint, generatingReview, reviewNotice, loaded) { busy, hint, reviewBusy, notice, isLoaded ->
        Flags(busy, hint, reviewBusy, notice, !isLoaded)
    }

    /** 实际生效的 AI 配置，复刻 SettingsRepository.currentAiConfig 的 Debug 兜底逻辑。 */
    private val aiSettings: Flow<AiConfig> = app.settings.aiConfig.map { stored ->
        if (stored.valid) {
            stored
        } else if (BuildConfig.DEBUG && BuildConfig.AUTO_AI_BASE_URL.isNotBlank() && BuildConfig.AUTO_AI_MODEL.isNotBlank()) {
            stored.copy(baseUrl = BuildConfig.AUTO_AI_BASE_URL, model = BuildConfig.AUTO_AI_MODEL, apiKey = BuildConfig.AUTO_AI_KEY)
        } else {
            stored
        }
    }

    private val extras = combine(app.database.todoDao().observeActive(), aiSettings) { todos, config ->
        Extras(todos, config.model, config.valid)
    }

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

    /**
     * 写作统计是「全库」口径，与正在看哪个月无关，所以不塞进下面那个大 combine。
     * 挂在 [stats] 后面是为了跟着数据变化重算：那个流本来就订阅了库表。
     */
    val writingStats: StateFlow<WritingStats?> = stats
        .map { app.journal.writingStats() }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** 情绪与标签的关联，同样挂在 stats 后面跟着数据变化重算。 */
    val moodTags: StateFlow<List<MoodTagLink>> = stats
        .map { app.journal.moodTagCorrelation() }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val uiState: StateFlow<InsightsUiState> = combine(
        stats,
        monthOffset,
        combine(messages, flags) { msgs, flag -> Conversation(msgs, flag) },
        cachedReview,
        extras,
    ) { stat, offset, conversation, review, extra ->
        val currentUids = stat.entries.map { it.uid }.toSet()
        val storedUids = runCatching {
            val array = JSONArray(review?.sourceUidsJson ?: "[]")
            (0 until array.length()).map { array.getString(it) }.toSet()
        }.getOrDefault(emptySet())
        // 存下来的来源 uid 和本月现状不一致，说明记录被新增/编辑/删除过，回顾已经过期。
        val stale = review != null && storedUids != currentUids
        val modelChanged = review != null && review.model.isNotBlank() && extra.model.isNotBlank() && review.model != extra.model
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
            asking = conversation.flags.asking,
            askHint = conversation.flags.askHint,
            suggestions = buildSuggestions(stat, extra.todos),
            aiConfigured = extra.aiConfigured,
            currentModel = extra.model,
            review = review,
            generatingReview = conversation.flags.generatingReview,
            reviewNotice = conversation.flags.notice
                ?: if (stale) ReviewNotice("记录已变化，建议重新生成。", ReviewSeverity.WARN, ReviewAction.REGENERATE) else null,
            reviewModelChanged = modelChanged,
            loading = conversation.flags.loaded,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), InsightsUiState())

    fun prevMonth() { if (monthOffset.value < 24) monthOffset.value += 1 }
    fun nextMonth() { if (monthOffset.value > 0) monthOffset.value -= 1 }

    /** 一次回到当月。往回翻几个月后只能一格格点回来，这个入口必须有。 */
    fun currentMonth() { monthOffset.value = 0 }

    /**
     * 提问。返回 false 表示这次输入没有被受理（空文本或上一问还在进行），
     * 界面据此保留输入框内容而不是把它清空。
     */
    fun ask(question: String): Boolean {
        val value = question.trim()
        if (value.isEmpty()) return false
        if (asking.value) {
            askHint.value = "正在回答上一个问题"
            return false
        }
        askHint.value = ""
        val history = messages.value.takeLast(HISTORY_TURNS).map { it.role to it.text }
        val turnId = nextMessageId++
        // 先把问题落盘（PENDING），再发请求：请求途中被杀，重进时还能看到问题并点按重试。
        appendTurn(ChatTurn(id = turnId, role = "user", text = value, question = value, status = TurnStatus.PENDING))
        startAsk(value, history, turnId, null)
        return true
    }

    /** 重新生成某条回答：用存下来的问题和当时的上下文重问，不必让用户重打一遍。 */
    fun regenerate(assistantId: Long) {
        if (asking.value) {
            askHint.value = "正在回答上一个问题"
            return
        }
        val index = messages.value.indexOfFirst { it.id == assistantId }
        if (index < 0) return
        val turn = messages.value[index]
        if (turn.role != "assistant") return
        val question = turn.question.ifBlank { messages.value.getOrNull(index - 1)?.text.orEmpty() }
        if (question.isBlank()) return
        val history = messages.value.take(index).takeLast(HISTORY_TURNS).map { it.role to it.text }
        askHint.value = ""
        startAsk(question, history, null, assistantId)
    }

    /** 重试被中断的问题。 */
    fun retry(messageId: Long) {
        if (asking.value) {
            askHint.value = "正在回答上一个问题"
            return
        }
        val index = messages.value.indexOfFirst { it.id == messageId }
        if (index < 0) return
        val turn = messages.value[index]
        if (turn.role != "user") return
        val question = turn.question.ifBlank { turn.text }
        val history = messages.value.take(index).takeLast(HISTORY_TURNS).map { it.role to it.text }
        setStatus(messageId, TurnStatus.PENDING)
        askHint.value = ""
        startAsk(question, history, messageId, null)
    }

    /** 停止当前提问。取消后把问题标成「已中断」，保留重试入口。 */
    fun cancelAsk() {
        askJob?.cancel()
    }

    /** 清空对话。聊天记录是持久化在本地的，必须给用户一个彻底的删除入口。 */
    fun clearChat() {
        askJob?.cancel()
        messages.value = emptyList()
        lastExcerpts = emptyList()
        askHint.value = ""
        viewModelScope.launch(Dispatchers.IO) { runCatching { chatFile.delete() } }
    }

    /** 保存用户对月度回顾的本地修改。之前注释写「不落库」，切个月份就丢了。 */
    fun saveReview(content: String) {
        val text = content.trim()
        if (text.isEmpty()) return
        val offset = monthOffset.value
        viewModelScope.launch {
            // 只取一次当前值，避免为了保存而长期订阅。
            val existing = app.database.aiReviewDao().observe(periodKey(offset)).first() ?: return@launch
            app.database.aiReviewDao().upsert(existing.copy(content = text))
        }
    }

    private fun startAsk(
        question: String,
        history: List<Pair<String, String>>,
        userTurnId: Long?,
        replaceAssistantId: Long?,
    ) {
        askJob = viewModelScope.launch {
            asking.value = true
            try {
                runAsk(question, history, userTurnId, replaceAssistantId)
            } catch (cancel: CancellationException) {
                // 离开页面或用户点了停止：留下痕迹，避免问题凭空消失。
                userTurnId?.let { setStatus(it, TurnStatus.INTERRUPTED) }
                if (userTurnId != null) putAssistant(null, "已取消", emptyList(), question, isAnswer = false)
                throw cancel
            } finally {
                asking.value = false
                askJob = null
            }
        }
    }

    private suspend fun runAsk(
        question: String,
        history: List<Pair<String, String>>,
        userTurnId: Long?,
        replaceAssistantId: Long?,
    ) {
        val config = app.settings.currentAiConfig()
        if (!config.valid) {
            userTurnId?.let { setStatus(it, TurnStatus.NORMAL) }
            putAssistant(replaceAssistantId, "还没配置 AI 服务。你仍可查看所有本地统计，配置后再来问我。", emptyList(), question, isAnswer = false)
            return
        }
        val retrieved = AskEngine.retrieve(dao, question, System.currentTimeMillis())
        // 追问（「那后来呢？」）检索命中常常为空：沿用上一轮摘录，引用编号才不会漂移。
        val excerpts = retrieved.ifEmpty { lastExcerpts }
        if (excerpts.isEmpty()) {
            userTurnId?.let { setStatus(it, TurnStatus.NORMAL) }
            putAssistant(replaceAssistantId, "暂时没有找到相关记忆。再记录一些，我会更了解你。", emptyList(), question, isAnswer = false)
            return
        }
        lastExcerpts = excerpts
        val prompt = AskEngine.buildUserPrompt(excerpts) + "\n\n【问题】$question"
        when (val result = AiClient.complete(config, AskEngine.SYSTEM_PROMPT, prompt, temperature = 0.5, history = history)) {
            is AiClient.Result.Ok -> {
                app.settings.recordAiUsage(result.promptTokens, result.completionTokens)
                // 只承认模型真正引用过的摘录：此前把每条摘录都挂成来源，是在编造出处。
                val cited = AskEngine.citedIndices(result.text)
                val sources = cited.mapNotNull { excerpts.getOrNull(it - 1)?.id }.distinct()
                userTurnId?.let { setStatus(it, TurnStatus.NORMAL) }
                putAssistant(replaceAssistantId, result.text, sources, question, isAnswer = true)
            }
            is AiClient.Result.Fail -> {
                userTurnId?.let { setStatus(it, TurnStatus.NORMAL) }
                putAssistant(replaceAssistantId, "回答失败：${result.message}", emptyList(), question, isAnswer = false)
            }
        }
    }

    private fun appendTurn(turn: ChatTurn) {
        messages.value = (messages.value + turn).takeLast(MAX_MESSAGES)
    }

    private fun setStatus(id: Long, status: TurnStatus) {
        messages.value = messages.value.map { if (it.id == id) it.copy(status = status) else it }
    }

    private fun putAssistant(id: Long?, text: String, sources: List<Long>, question: String, isAnswer: Boolean) {
        if (id != null) {
            var replaced = false
            messages.value = messages.value.map { turn ->
                if (turn.id == id) {
                    replaced = true
                    turn.copy(
                        text = text.trim(),
                        sourceEntryIds = sources,
                        question = question,
                        status = TurnStatus.NORMAL,
                        isAnswer = isAnswer,
                    )
                } else {
                    turn
                }
            }
            if (replaced) return
        }
        appendTurn(
            ChatTurn(
                id = nextMessageId++,
                role = "assistant",
                text = text.trim(),
                sourceEntryIds = sources,
                question = question,
                isAnswer = isAnswer,
            )
        )
    }

    private fun restoreMessages() {
        val restored = runCatching {
            if (!chatFile.exists()) return@runCatching emptyList<ChatTurn>()
            val array = JSONObject(chatFile.readText()).optJSONArray("messages") ?: return@runCatching emptyList<ChatTurn>()
            (0 until array.length()).mapNotNull { index ->
                val obj = array.optJSONObject(index) ?: return@mapNotNull null
                val stored = runCatching { TurnStatus.valueOf(obj.optString("status", TurnStatus.NORMAL.name)) }
                    .getOrDefault(TurnStatus.NORMAL)
                val sources = obj.optJSONArray("sources")?.let { arr -> (0 until arr.length()).map { arr.optLong(it) } }.orEmpty()
                ChatTurn(
                    id = obj.optLong("id"),
                    role = obj.optString("role"),
                    text = obj.optString("text"),
                    sourceEntryIds = sources,
                    question = obj.optString("question"),
                    // 上次没等到结果就退出了：降级为中断，用户点一下即可重试。
                    status = if (stored == TurnStatus.PENDING) TurnStatus.INTERRUPTED else stored,
                    isAnswer = obj.optBoolean("isAnswer", false),
                )
            }
        }.getOrDefault(emptyList())
        if (restored.isEmpty()) return
        nextMessageId = maxOf(restored.maxOf { it.id } + 1, nextMessageId)
        messages.value = restored.takeLast(MAX_MESSAGES)
    }

    private fun persistMessages(turns: List<ChatTurn>) {
        runCatching {
            val array = JSONArray()
            turns.takeLast(MAX_MESSAGES).forEach { turn ->
                array.put(
                    JSONObject()
                        .put("id", turn.id)
                        .put("role", turn.role)
                        .put("text", turn.text)
                        .put("question", turn.question)
                        .put("status", turn.status.name)
                        .put("isAnswer", turn.isAnswer)
                        .put("sources", JSONArray(turn.sourceEntryIds))
                )
            }
            chatFile.writeText(array.toString())
        }
    }

    fun generateMonthlyReview() {
        if (generatingReview.value) return
        viewModelScope.launch {
            generatingReview.value = true
            reviewNotice.value = null
            try {
                val offset = monthOffset.value
                val ym = month(offset)
                val zone = ZoneId.systemDefault()
                val entries = dao.between(
                    ym.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli(),
                    ym.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli(),
                )
                if (entries.isEmpty()) {
                    reviewNotice.value = ReviewNotice("${ym.monthValue}月还没有记录，先记几句再来生成回顾。", ReviewSeverity.WARN)
                    return@launch
                }
                val config = app.settings.currentAiConfig()
                if (!config.valid) {
                    reviewNotice.value = ReviewNotice(
                        "还没配置 AI 服务，到设置页填写接口地址和 Key 后即可生成回顾。",
                        ReviewSeverity.WARN,
                        ReviewAction.SETTINGS,
                    )
                    return@launch
                }
                // 之前写死「最近 80 条」，再被 12000 字截断，报出的条数就会是假的。
                // 现在按预算真正装入多少条就报多少条。
                val ordered = entries.sortedByDescending { it.createdAt }
                val included = mutableListOf<EntryEntity>()
                var length = 0
                for (entry in ordered) {
                    val line = reviewLine(entry)
                    if (length + line.length + 1 > REVIEW_BUDGET) break
                    included += entry
                    length += line.length + 1
                }
                val corpus = buildString {
                    append("【本月统计】\n")
                    append(reviewStatsBlock(ym, entries))
                    append("\n\n【记录摘录】\n")
                    append(included.joinToString("\n") { reviewLine(it) })
                }
                val system = "你是墨枢的月度回顾助手。仅根据记录，写一份温和、具体、不说教的中文回顾。包含：本月主线、值得肯定、需要留意、下月一个轻量建议。总计不超过350字。"
                when (val result = AiClient.complete(config, system, corpus, temperature = 0.5, maxTokens = 900)) {
                    is AiClient.Result.Ok -> {
                        app.settings.recordAiUsage(result.promptTokens, result.completionTokens)
                        app.database.aiReviewDao().upsert(
                            AiReviewEntity(
                                periodKey = periodKey(offset),
                                periodType = "month",
                                content = result.text,
                                sourceUidsJson = JSONArray(entries.map { it.uid }).toString(),
                                model = config.model,
                                promptVersion = REVIEW_PROMPT_VERSION,
                                entryCount = included.size,
                            )
                        )
                        if (included.size < entries.size) {
                            reviewNotice.value = ReviewNotice(
                                "本月记录较多，回顾基于最近 ${included.size} 条生成。",
                                ReviewSeverity.INFO,
                            )
                        }
                    }
                    is AiClient.Result.Fail -> reviewNotice.value = ReviewNotice("生成失败：${result.message}", ReviewSeverity.ERROR)
                }
            } finally {
                generatingReview.value = false
            }
        }
    }

    /** 长条目用摘要 + 标签 + 心情代替 180 字正文切片，信息密度更高也更省预算。 */
    private fun reviewLine(entry: EntryEntity): String {
        val day = SimpleDateFormat("M月d日", Locale.CHINA).format(Date(entry.createdAt))
        val body = if (entry.summary.isNotBlank()) entry.summary.take(120) else entry.content.take(180)
        val meta = buildList {
            parseTags(entry.tagsJson).take(3).forEach { add("#$it") }
            MOOD_LABELS[entry.mood]?.let { add(it) }
        }.joinToString(" ")
        return "- $day${if (meta.isNotBlank()) " $meta" else ""}：$body"
    }

    private fun reviewStatsBlock(ym: YearMonth, entries: List<EntryEntity>): String {
        val scored = entries.mapNotNull { MOOD_SCORES[it.mood] }
        val activeDays = entries.map { dayOfMonth(it.createdAt) }.toSet().size
        val streak = calculateStreak(entries, minOf(ym.atEndOfMonth(), LocalDate.now()))
        val categories = Category.NAMES.indices
            .map { Category.NAMES[it] to entries.count { entry -> entry.categoryId == it } }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
        val tags = entries.flatMap { parseTags(it.tagsJson) }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .take(5)
        return buildString {
            append("- 记录 ${entries.size} 条，活跃 $activeDays 天，连续记录 $streak 天")
            if (scored.isNotEmpty()) append("，平均心情 ${"%.1f".format(Locale.CHINA, scored.average())}/5")
            if (categories.isNotEmpty()) {
                append("\n- 分类分布：")
                append(categories.joinToString("，") { "${it.first} ${it.second} 条" })
            }
            if (tags.isNotEmpty()) {
                append("\n- 高频标签：")
                append(tags.joinToString("、") { "${it.key}(${it.value})" })
            }
        }
    }

    private fun buildSuggestions(stat: Stats, todos: List<TodoEntity>): List<String> {
        val items = mutableListOf<String>()
        val topCategory = stat.categories.indices.maxByOrNull { stat.categories[it] }
        if (topCategory != null && stat.categories[topCategory] > 0) {
            items += "最近在「${Category.NAMES[topCategory]}」上花的时间最多，都发生了什么？"
        }
        todos.firstOrNull { it.dueEpochDay != null }?.let { todo ->
            val due = LocalDate.ofEpochDay(todo.dueEpochDay!!.toLong())
            val diff = due.toEpochDay() - LocalDate.now().toEpochDay()
            val whenText = when {
                diff < 0 -> "已经过期"
                diff == 0L -> "今天到期"
                diff == 1L -> "明天到期"
                else -> "${diff}天后到期"
            }
            items += "「${todo.text.take(16)}」$whenText，打算怎么处理？"
        }
        if (stat.streak > 0) items += "我已经连续记录 ${stat.streak} 天了，这段时间状态如何？"
        // 数据不足时补足三条，避免只剩一两个建议。
        items += "最近在忙什么？"
        items += "这个月心情怎样？"
        items += "我最近都记了些什么？"
        return items.distinct().take(3)
    }

    companion object {
        val MOOD_SCORES = mapOf("great" to 5.0, "good" to 4.0, "neutral" to 3.0, "low" to 2.0, "bad" to 1.0)

        val MOOD_LABELS = mapOf(
            "great" to "心情很好",
            "good" to "心情不错",
            "neutral" to "心情一般",
            "low" to "有点低落",
            "bad" to "心情很差",
        )

        /** 本地对话记录上限，避免文件无限增长。 */
        const val MAX_MESSAGES = 200

        /** 传给模型的历史轮数。 */
        const val HISTORY_TURNS = 6

        /** 月度回顾语料的字符预算。 */
        const val REVIEW_BUDGET = 12_000

        /** 回顾提示词版本，配合 AiReviewEntity.promptVersion 判断是否需要重做。 */
        const val REVIEW_PROMPT_VERSION = 2

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