package app.moshu.journal.ai

import app.moshu.journal.data.db.Category
import app.moshu.journal.data.db.EntryEntity
import app.moshu.journal.data.db.ManualMetadata
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 条目整理引擎：把随手记的一段话交给 LLM，
 * 归类（生活/工作/灵感）、打标签、生成一句话概括、判断情绪。
 */
object Enricher {

    /** 提示词契约版本。改动 SYSTEM_PROMPT 或解析收敛规则时递增，设置页可据此找出需要重做的条目。 */
    const val PROMPT_VERSION = 2

    /** 整理默认的输出预算：结构化 JSON 用不了太多 token，给足即可。 */
    const val DEFAULT_MAX_TOKENS = 900

    private val SYSTEM_PROMPT = """
你是「墨枢」的日志整理引擎。用户给你一段随手记下的文字，请输出严格的 JSON（禁止 markdown 代码块），格式：
{"category":"life|work|idea","tags":["标签"],"summary":"不超过20字的概括","mood":"great|good|neutral|low|bad","todos":[{"text":"待办内容","due":"YYYY-MM-DD"}]}

规则：
- category：日常琐事/饮食/健康/家人朋友/消费=life；任务/会议/学习/职业发展=work；点子/创作/脑洞/计划=idea
- tags：提取 1~4 个具体短标签，如「加班」「健身」「电影」；必须是名词或名词短语，不含标点，不超过 6 字
- summary：用第三人称概括这条记录的核心信息，不超过 20 字
- mood：依据整体情绪判断，没有明显情绪时用 neutral
- todos：只提取文中明确承诺或计划要做的事，每条不超过 30 字、动词开头；没有则为空数组 []；due 仅当文中出现明确日期时给出，否则省略该字段

示例：
输入：下午去牙科复诊，医生说少喝咖啡；还想把借的书还回图书馆。
输出：{"category":"life","tags":["看牙","健康"],"summary":"牙科复诊并计划还书","mood":"neutral","todos":[{"text":"去图书馆还书"}]}
""".trim()

    data class TodoDraft(val text: String, val due: String?)

    data class Enrichment(
        val categoryId: Int,
        val tags: List<String>,
        val summary: String,
        val mood: String,
        val todos: List<TodoDraft> = emptyList(),
    )

    fun parse(raw: String): Enrichment? = try {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) null else {
            val obj = JSONObject(raw.substring(start, end + 1))
            val rawTags = buildList {
                val arr = obj.optJSONArray("tags") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    // 用 optString 逐个取值：模型偶尔会把数字或 null 混进数组，
                    // 用 getString 会抛异常，导致整条整理结果（含概括、情绪、行动项）全丢。
                    val tag = arr.optString(i, "")
                    if (tag.isNotBlank()) add(tag)
                }
            }

            val todos = buildList {
                val arr = obj.optJSONArray("todos") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val t = arr.optJSONObject(i) ?: continue
                    val text = t.optString("text").trim()
                    if (text.isNotEmpty()) add(TodoDraft(text.take(40), t.optString("due", "").ifBlank { null }))
                }
            }.take(10)

            Enrichment(
                categoryId = categoryIdOf(obj.optString("category")),
                tags = normalizeTags(rawTags),
                summary = obj.optString("summary").trim().take(24),
                mood = normalizeMood(obj.optString("mood")),
                todos = todos,
            )
        }
    } catch (_: Exception) {
        null
    }

    /**
     * 只接受已知情绪值。模型偶尔会返回 happy / sad 这类同义写法，
     * 直接落库会让条目在回顾页没有表情、也不计入平均心情——属于静默的数据损坏。
     */
    fun normalizeMood(raw: String): String {
        val value = raw.trim().lowercase(Locale.ROOT)
        return if (value in MOODS) value else "neutral"
    }

    /**
     * 分类收敛。模型常回 job / 职场 / 创意 这类别名，直接落到 LIFE 会把工作笔记
     * 静默归错类，回顾页的分类分布也跟着失真。只对真正无法识别的值回退 LIFE。
     */
    fun categoryIdOf(raw: String): Int = when (raw.trim().lowercase(Locale.ROOT)) {
        "work", "job", "工作", "职场", "任务", "学习", "职业" -> Category.WORK
        "idea", "创意", "灵感", "想法", "点子" -> Category.IDEA
        "life", "health", "生活", "家人", "日常" -> Category.LIFE
        else -> Category.LIFE
    }

    /**
     * 标签收敛：去首尾标点、丢过长的与空泛词、去重、限量。
     * 不收敛的话同一个概念会变成「加班/工作加班/熬夜加班」三个标签，标签筛选直接失效。
     */
    fun normalizeTags(raw: List<String>): List<String> = raw
        .map { it.trim().trim(*TAG_TRIM_CHARS).trim() }
        .filter { it.isNotEmpty() && it.length <= 8 && it !in TAG_STOPLIST }
        .distinctBy { it.lowercase(Locale.ROOT) }
        .take(4)

    /** 从落库的 tagsJson 还原标签列表，坏数据一律当成空。 */
    fun tagsOf(tagsJson: String): List<String> = runCatching {
        val arr = JSONArray(tagsJson)
        buildList {
            for (i in 0 until arr.length()) {
                arr.optString(i, "").takeIf { it.isNotBlank() }?.let { add(it) }
            }
        }
    }.getOrDefault(emptyList())

    /** 统计用户最常用的标签，回填进提示词以促使模型复用既有词汇。 */
    fun topTags(tagJsonList: List<String>, limit: Int = 12): List<String> =
        tagJsonList.flatMap { tagsOf(it) }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(limit)
            .map { it.key }

    /**
     * 待办日期归一化：模型可能给 2026-09-25、2026/9/25、9月25日、9-25 四种写法。
     * 只认 ISO 会让「周五交」「9月25日」这类最常见的表述被静默丢弃。
     */
    fun parseDue(value: String?, today: LocalDate = LocalDate.now()): Int? {
        val text = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        runCatching { LocalDate.parse(text, DateTimeFormatter.ISO_LOCAL_DATE) }.getOrNull()?.let {
            return it.toEpochDay().toInt()
        }
        for (pattern in DUE_PATTERNS) {
            runCatching { LocalDate.parse(text, DateTimeFormatter.ofPattern(pattern, Locale.ROOT)) }.getOrNull()?.let {
                return it.toEpochDay().toInt()
            }
        }
        val monthDay = MONTH_DAY.find(text)
        if (monthDay != null) {
            return rollForward(today, monthDay.groupValues[1].toInt(), monthDay.groupValues[2].toInt())
        }
        val shortMonthDay = SHORT_MONTH_DAY.find(text)
        if (shortMonthDay != null) {
            return rollForward(today, shortMonthDay.groupValues[1].toInt(), shortMonthDay.groupValues[2].toInt())
        }
        return null
    }

    /** 无年份的日期按当前年补全；已经过去的日期顺延到明年。 */
    private fun rollForward(today: LocalDate, month: Int, day: Int): Int? = runCatching {
        val date = LocalDate.of(today.year, month, day)
        (if (date.isBefore(today)) date.plusYears(1) else date).toEpochDay().toInt()
    }.getOrNull()

    /**
     * 计算用户本次编辑真正改动了哪些字段，返回 [ManualMetadata] 位掩码。
     * 仓库层必须把这个掩码 OR 进 manualMetadataMask，而不是无条件标记全部字段——
     * 后者会让用户第一次编辑之后 AI 的改进永远不再生效，且界面上无法解冻。
     * `content` 只用于调用方判断是否要重新整理，不产出对应的冻结位。
     */
    @Suppress("UNUSED_PARAMETER")
    fun changedFields(
        original: EntryEntity,
        content: String,
        categoryId: Int,
        tags: List<String>,
        mood: String,
        summary: String,
    ): Int {
        var mask = 0
        if (categoryId != original.categoryId) mask = mask or ManualMetadata.CATEGORY
        if (normalizeTags(tags) != tagsOf(original.tagsJson)) mask = mask or ManualMetadata.TAGS
        if (mood.trim() != original.mood) mask = mask or ManualMetadata.MOOD
        if (summary.trim() != original.summary) mask = mask or ManualMetadata.SUMMARY
        return mask
    }

    /**
     * 解析失败时的兜底：让模型自己把残缺输出修成合法 JSON。
     * 只做一次，温度 0；修不出来才判定为失败，避免整条整理结果因一个尾部逗号全丢。
     */
    suspend fun repair(raw: String, config: AiConfig): String? {
        val result = AiClient.complete(
            config = config,
            system = "你是 JSON 修复器。只输出修正后的 JSON，不要解释。",
            user = "下面是模型输出，请修复为合法 JSON：\n$raw",
            temperature = 0.0,
        )
        return (result as? AiClient.Result.Ok)?.text
    }

    suspend fun enrich(
        config: AiConfig,
        content: String,
        images: List<AiClient.ImageInput> = emptyList(),
        knownTags: List<String> = emptyList(),
        maxTokens: Int = DEFAULT_MAX_TOKENS,
    ): Pair<AiClient.Result, Enrichment?> {
        val user = buildUserMessage(content, knownTags)
        val result = AiClient.complete(
            config = config,
            system = SYSTEM_PROMPT,
            user = user,
            temperature = 0.2,
            images = images,
            maxTokens = maxTokens,
        )
        var enrichment = (result as? AiClient.Result.Ok)?.let { parse(it.text) }
        if (enrichment == null) {
            val raw = (result as? AiClient.Result.Ok)?.text ?: return result to null
            enrichment = repair(raw, config)?.let { parse(it) }
        }
        return result to enrichment
    }

    /** 相对日期必须给模型一个锚点，否则「周五交」这类待办要么被丢、要么被算到错误的年份。 */
    private fun buildUserMessage(content: String, knownTags: List<String>): String {
        val today = LocalDate.now()
        val weekday = "周" + "一二三四五六日"[today.dayOfWeek.value - 1]
        return buildString {
            append("当前时间：").append(today).append("（").append(weekday).append("）。")
            append("遇到「明天/周五/下月」等相对日期，换算为 YYYY-MM-DD 后填入 due。\n")
            if (knownTags.isNotEmpty()) {
                append("已有标签：").append(knownTags.joinToString("、"))
                append("。若语义与已有标签相同，必须复用已有标签原文；标签必须是名词或名词短语，不含标点，不超过 6 字。\n")
            }
            append("\n随手记：\n").append(content)
        }
    }

    private val MOODS = setOf("great", "good", "neutral", "low", "bad")

    private val TAG_STOPLIST = setOf("生活", "日常", "记录", "其他", "心情")
    private val TAG_TRIM_CHARS = charArrayOf('#', '「', '」', '"', '\'', '“', '”', '‘', '’')

    private val DUE_PATTERNS = listOf("yyyy/M/d", "yyyy-M-d")
    private val MONTH_DAY = Regex("(\\d{1,2})月(\\d{1,2})日")
    private val SHORT_MONTH_DAY = Regex("(\\d{1,2})-(\\d{1,2})")
}