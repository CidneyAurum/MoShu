package app.moshu.journal.ai

import app.moshu.journal.data.db.EntryDao
import app.moshu.journal.data.db.EntryEntity
import java.text.SimpleDateFormat
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.util.Date
import java.util.Locale

/**
 * M4 对话式回顾（RAG）。
 * 检索策略：中文按二字组切分后 LIKE 命中，按命中数相关度排序，再补近 14 天兜底上下文，
 * 在固定字符预算内拼成带编号的摘录交给 LLM 作答。
 * （向量检索留待接入支持 embeddings 的服务商后升级）
 */
object AskEngine {

    data class ChatMessage(
        val role: String, // "user" | "assistant"
        val text: String,
        val sourceEntryIds: List<Long> = emptyList(),
        /** 列表身份标识。同一句话可以问两次，因此不能用文本做 key。 */
        val id: Long = 0L,
    ) {
        val sourceCount: Int get() = sourceEntryIds.size
    }

    /** 一条带编号的摘录。nearContext=true 表示只是近 14 天兜底，与问题不一定直接相关。 */
    data class Excerpt(val entry: EntryEntity, val score: Int, val nearContext: Boolean)

    /** 问题里解析出的时间范围；端点为闭开区间，与 EntryDao.between 一致。 */
    data class DateRange(val start: Long, val end: Long, val label: String)

    /** 摘录的总字符预算。小上下文模型（如 moonshot-v1-8k）也能安全装下。 */
    const val MAX_CONTEXT_CHARS = 6000

    val SYSTEM_PROMPT = """
你是「墨枢」，用户的私人日记助手。
仅依据下方带编号的摘录回答。每条摘录形如 [3] 8月20日：…。回答中必须用 [3] 标注每一处事实来源；不要引用未给出的编号。摘录不足以回答时直接说不知道。
用中文回答，口语化、简洁。
""".trim()

    /**
     * 从提问中抽取检索关键词。
     * 中文按二字组（bigram）切分：整段连续汉字做 LIKE 永远匹配不到任何一条记录，
     * 拆成二字组才能命中「健身」「计划」这类句中词；英文/数字词保持原样。
     */
    fun extractKeywords(question: String): List<String> {
        val result = LinkedHashSet<String>()
        for (match in KEYWORD_PATTERN.findAll(question)) {
            val value = match.value
            if (value.all { it.code < 128 }) {
                result.add(value.lowercase(Locale.ROOT))
                continue
            }
            if (value.length < 2) continue
            for (index in 0..value.length - 2) {
                val bigram = value.substring(index, index + 2)
                if (bigram !in KEYWORD_STOPWORDS) result.add(bigram)
            }
        }
        return result.filterNot { it in KEYWORD_STOPWORDS }.take(MAX_KEYWORDS)
    }

    /**
     * 解析问题里的时间表达，供检索取数与提示词头部标注。
     * 认不出来时返回 null，走纯关键词检索。
     */
    fun parseDateRange(question: String, today: LocalDate = LocalDate.now()): DateRange? {
        val explicitYear = YEAR.find(question)
        return when {
            "上周" in question -> weekRange(today.minusWeeks(1))
            "本周" in question || "这周" in question -> weekRange(today)
            "上个月" in question || "上月" in question -> monthRange(YearMonth.from(today).minusMonths(1))
            "这个月" in question || "本月" in question -> monthRange(YearMonth.from(today))
            "去年" in question -> yearRange(today.year - 1)
            explicitYear != null -> yearRange(explicitYear.groupValues[1].toInt())
            else -> null
        }
    }

    /** 兼容旧调用：只需要条目列表的调用方走这里。 */
    suspend fun retrieve(
        dao: EntryDao,
        question: String,
        now: Long,
        maxChars: Int = MAX_CONTEXT_CHARS,
    ): List<EntryEntity> = collectExcerpts(dao, question, now, maxChars).map { it.entry }

    /**
     * 检索并按相关度排序摘录。
     * 关键词命中数（正文/概括/标签加权）是主序，时间只做同分兜底；
     * 否则去年命中的那条永远输给昨天一条无关的记录。
     */
    suspend fun collectExcerpts(
        dao: EntryDao,
        question: String,
        now: Long,
        maxChars: Int = MAX_CONTEXT_CHARS,
    ): List<Excerpt> {
        val keywords = extractKeywords(question)
        val range = parseDateRange(question)
        val candidates = LinkedHashMap<Long, EntryEntity>()

        // 有时限的问题直接按日期区间取数，命中的条目天然属于该时间段。
        range?.let { dao.between(it.start, it.end).forEach { entry -> candidates[entry.id] = entry } }
        for (keyword in keywords) {
            // 关键词会直接拼进 LIKE 模式串，通配符必须转义。
            dao.searchOnce(escapeLikePattern(keyword), 20).forEach { entry -> candidates[entry.id] = entry }
        }

        val scored = candidates.values
            .map { Excerpt(it, score(it, keywords), nearContext = false) }
            .filter { it.score > 0 || range != null }
            .sortedWith(compareByDescending<Excerpt> { it.score }.thenByDescending { it.entry.createdAt })

        val selected = mutableListOf<Excerpt>()
        val usedChars = intArrayOf(0)
        val seenPrefixes = mutableSetOf<String>()
        val selectedIds = mutableSetOf<Long>()
        val perDay = mutableMapOf<LocalDate, Int>()
        val deferred = mutableListOf<Excerpt>()

        fun tryAdd(excerpt: Excerpt, enforceDayCap: Boolean): Boolean {
            if (!selectedIds.add(excerpt.entry.id)) return false
            val key = prefixKey(excerpt.entry.content)
            if (key.isNotEmpty() && key in seenPrefixes) {
                selectedIds.remove(excerpt.entry.id)
                return false
            }
            val day = dayOf(excerpt.entry.createdAt)
            if (enforceDayCap && (perDay[day] ?: 0) >= MAX_PER_DAY) {
                selectedIds.remove(excerpt.entry.id)
                deferred += excerpt
                return false
            }
            val cost = estimateChars(excerpt.entry)
            if (selected.isNotEmpty() && usedChars[0] + cost > maxChars) {
                selectedIds.remove(excerpt.entry.id)
                return false
            }
            selected += excerpt
            usedChars[0] += cost
            if (key.isNotEmpty()) seenPrefixes += key
            perDay[day] = (perDay[day] ?: 0) + 1
            return true
        }

        // 第一轮限制单日条数，逼出跨天的多样性；被挤掉的在第二轮补回。
        scored.forEach { tryAdd(it, enforceDayCap = true) }
        deferred.forEach { tryAdd(it, enforceDayCap = false) }

        // 近两周条目在预算内兜底，保证「最近在忙什么」这类泛问也能答；
        // 它们是上下文而不是答案，单独标注让模型区别对待。
        var fallbackAdded = 0
        for (entry in dao.since(now - FALLBACK_WINDOW_MILLIS)) {
            if (fallbackAdded >= FALLBACK_BUDGET) break
            if (tryAdd(Excerpt(entry, score = 0, nearContext = true), enforceDayCap = false)) {
                fallbackAdded += 1
            }
        }
        return selected
    }

    fun buildPrompt(excerpts: List<Excerpt>, question: String = ""): String {
        val range = parseDateRange(question)
        val keywords = extractKeywords(question)
        val dayFormat = SimpleDateFormat("M月d日", Locale.CHINA)
        return buildString {
            append("【日记摘录】")
            if (range != null) append("以下摘录时间范围：").append(range.label)
            append('\n')
            excerpts.forEachIndexed { index, excerpt ->
                if (excerpt.nearContext && (index == 0 || !excerpts[index - 1].nearContext)) {
                    append("以下为近期上下文（与问题不一定直接相关）：\n")
                }
                append('[').append(index + 1).append("] ")
                    .append(dayFormat.format(Date(excerpt.entry.createdAt))).append('：')
                if (excerpt.entry.summary.isNotBlank()) append(excerpt.entry.summary)
                append('\n')
                val tags = Enricher.tagsOf(excerpt.entry.tagsJson)
                if (tags.isNotEmpty()) append("标签：").append(tags.joinToString("、")).append('\n')
                append(excerptWindow(excerpt.entry.content, keywords)).append('\n')
            }
        }.trim()
    }

    /** 兼容旧调用：不区分兜底上下文，也不带时间范围。新调用方请用 collectExcerpts + buildPrompt。 */
    fun buildUserPrompt(excerpts: List<EntryEntity>, question: String = ""): String =
        buildPrompt(excerpts.map { Excerpt(it, score = 0, nearContext = false) }, question)

    /** 从回答里提取 [n] 编号，供调用方只回挂真正被引用的条目，而不是把全部摘录都当来源。 */
    fun citedIndices(answer: String): List<Int> = CITATION.findAll(answer)
        .mapNotNull { it.groupValues[1].toIntOrNull() }
        .filter { it >= 1 }
        .distinct()
        .toList()

    private fun score(entry: EntryEntity, keywords: List<String>): Int {
        if (keywords.isEmpty()) return 0
        val tags = Enricher.tagsOf(entry.tagsJson).joinToString(" ")
        var total = 0
        for (keyword in keywords) {
            if (entry.content.contains(keyword, ignoreCase = true)) total += 3
            if (entry.summary.contains(keyword, ignoreCase = true)) total += 2
            if (tags.contains(keyword, ignoreCase = true)) total += 2
        }
        return total
    }

    /** 正文窗口：以第一个关键词命中为中心，前 60 后 120 字，避免关键句被 150 字截断切掉。 */
    private fun excerptWindow(content: String, keywords: List<String>): String {
        if (content.length <= EXCERPT_MAX_CHARS) return content
        val hit = keywords.map { content.indexOf(it, ignoreCase = true) }.filter { it >= 0 }.minOrNull()
            ?: return content.take(EXCERPT_MAX_CHARS)
        val start = (hit - EXCERPT_BEFORE).coerceAtLeast(0)
        val end = (hit + EXCERPT_AFTER).coerceAtMost(content.length)
        return content.substring(start, end)
    }

    private fun estimateChars(entry: EntryEntity): Int {
        val tags = Enricher.tagsOf(entry.tagsJson).sumOf { it.length + 1 }
        val body = minOf(entry.content.length, EXCERPT_MAX_CHARS)
        return body + entry.summary.length + tags + EXCERPT_OVERHEAD
    }

    /** 去空白后取前 60 字做近似判重：日志里常有五条几乎一样的记录，不能一起挤占预算。 */
    private fun prefixKey(content: String): String =
        content.take(60).filterNot { it.isWhitespace() }.lowercase(Locale.ROOT)

    private fun dayOf(timestamp: Long): LocalDate =
        Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).toLocalDate()

    private fun weekRange(anyDayInWeek: LocalDate): DateRange {
        val monday = anyDayInWeek.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        return DateRange(startOfDay(monday), startOfDay(monday.plusWeeks(1)), rangeLabel(monday, monday.plusDays(6)))
    }

    private fun monthRange(month: YearMonth): DateRange {
        val start = month.atDay(1)
        return DateRange(startOfDay(start), startOfDay(month.plusMonths(1).atDay(1)), rangeLabel(start, month.atEndOfMonth()))
    }

    private fun yearRange(year: Int): DateRange {
        val start = LocalDate.of(year, 1, 1)
        val end = LocalDate.of(year, 12, 31)
        // 整年区间必须带上年份：用户问「去年 / 2024年」时，年份才是关键信息，
        // 而通用 rangeLabel 在同一年时会省略它。
        return DateRange(startOfDay(start), startOfDay(start.plusYears(1)), "${year}年1月1日–${end.monthValue}月${end.dayOfMonth}日")
    }

    private fun startOfDay(date: LocalDate): Long =
        date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun rangeLabel(start: LocalDate, end: LocalDate): String = if (start.year == end.year) {
        "${start.monthValue}月${start.dayOfMonth}日–${end.monthValue}月${end.dayOfMonth}日"
    } else {
        "${start.year}年${start.monthValue}月${start.dayOfMonth}日–${end.year}年${end.monthValue}月${end.dayOfMonth}日"
    }

    /** 与 EntryDao 的 LIKE ... ESCAPE '!' 配套：转义通配符，否则关键词里的 % 会匹配全部。 */
    private fun escapeLikePattern(value: String): String = value
        .replace("!", "!!")
        .replace("%", "!%")
        .replace("_", "!_")

    private val KEYWORD_PATTERN = Regex("[A-Za-z0-9]{2,}|[\\u4e00-\\u9fa5]+")
    private val KEYWORD_STOPWORDS = setOf("的", "了", "吗", "呢", "怎么", "什么", "最近", "现在", "怎么样", "说说", "一下", "是不是")
    private val YEAR = Regex("(\\d{4})年")
    private val CITATION = Regex("\\[(\\d+)\\]")

    private const val MAX_KEYWORDS = 12
    private const val MAX_PER_DAY = 2
    private const val FALLBACK_BUDGET = 10
    private const val EXCERPT_BEFORE = 60
    private const val EXCERPT_AFTER = 120
    private const val EXCERPT_MAX_CHARS = EXCERPT_BEFORE + EXCERPT_AFTER
    private const val EXCERPT_OVERHEAD = 24
    private const val FALLBACK_WINDOW_MILLIS = 14L * 24 * 60 * 60 * 1000
}