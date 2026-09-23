package app.moshu.journal.data.schedule

import app.moshu.journal.ai.AiClient
import app.moshu.journal.ai.AiConfig
import app.moshu.journal.data.db.EventEntity
import app.moshu.journal.data.db.RepeatRule
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/**
 * 用 AI 把一句话解析成日程。
 *
 * 分工：本地 [EventIntentParser] 先跑，命中足够信息就直接用（离线、瞬时、确定）；
 * 本地解析不出日期时（「下周组会前一晚」这类相对表达）才交给模型。
 *
 * 模型返回的日期一律**再校验一遍**：让它给出「星期几 + 第几周」这类结构化字段，
 * 由本地换算成绝对日期，而不是直接信任它算好的时间戳。
 * 让语言模型做日期算术是已知的不可靠来源，而这里算错一天用户就会错过事情。
 */
object AiEventPlanner {

    data class Result(
        val intent: EventIntentParser.Intent,
        /** 是否由 AI 解析（用于界面说明「这次用了模型」）。 */
        val fromAi: Boolean,
        /** 失败原因；成功时为空。 */
        val error: String = "",
    )

    private const val MAX_TITLE_CHARS = 60

    /**
     * 解析。[fallback] 是本地解析结果。
     *
     * 本地已经拿到日期就直接返回，不调用模型——省一次调用，也避免模型把正确的日期改坏。
     */
    suspend fun plan(
        text: String,
        config: AiConfig,
        now: LocalDateTime = LocalDateTime.now(),
        zone: ZoneId = ZoneId.systemDefault(),
        fallback: EventIntentParser.Intent = EventIntentParser.parse(text, now, zone),
    ): Result {
        if (fallback.day != null) return Result(fallback, fromAi = false)
        if (!config.valid) {
            return Result(fallback, fromAi = false, error = "这句话里没读出日期，且未配置 AI 服务，无法进一步理解。")
        }

        val answer = runCatching {
            AiClient.complete(
                config = config,
                system = SYSTEM_PROMPT,
                user = buildUserMessage(text, now, zone),
                temperature = 0.0,
                maxTokens = 300,
                jsonMode = true,
            )
        }.getOrElse { error ->
            // 异常文案交给 AiClient 统一成中文，与其它 AI 入口保持一致。
            return Result(fallback, fromAi = false, error = error.message ?: "调用 AI 失败")
        }

        val ok = answer as? AiClient.Result.Ok
            ?: return Result(fallback, fromAi = false, error = (answer as? AiClient.Result.Fail)?.message ?: "模型没有返回结果")

        val parsed = runCatching { parseModelJson(ok.text, now, zone, text) }.getOrNull()
            ?: return Result(fallback, fromAi = false, error = "模型返回的内容无法解析成日程，可以手动安排。")

        return Result(parsed, fromAi = true)
    }

    private const val SYSTEM_PROMPT = """你在把一句中文口语转成日程字段。只输出 JSON，不要解释。
输出格式：
{"title":"要做什么","date":"YYYY-MM-DD 或 null","time":"HH:mm 或 null","allDay":true或false,"repeat":"none|daily|weekly|monthly|yearly","reminderOffsetMinutes":整数}

规则：
1. 日期必须结合下面给出的「当前时间」换算成绝对日期。
2. 相对表达按此理解：本周X = 当前这一周的星期X（若已过去则取下周）；下周X = 下一周；周末 = 最近的周六。
3. time 用 24 小时制。用户说「晚上八点」是 20:00；只说「八点」且无时段词时按字面 08:00。
4. 没提到时间时 allDay 为 true、time 为 null。
5. reminderOffsetMinutes 是「提前多少分钟提醒」；用户说「提前半小时」就是 30。没提就按：有具体时刻填 60，全天填 540。
6. title 只保留「要做什么」，去掉时间词、语气词（记得/别忘了）。
7. 无法确定日期时 date 填 null，不要猜。"""

    private fun buildUserMessage(text: String, now: LocalDateTime, zone: ZoneId): String {
        val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd EEEE HH:mm", Locale.CHINA)
        return "当前时间：${now.atZone(zone).format(fmt)}（时区 ${zone.id}）\n用户说：$text"
    }

    /**
     * 解析模型返回的 JSON。
     *
     * 日期做二次校验：解析失败或落在过去超过一年的，一律视为无效——
     * 宁可让用户手动填，也不要静默排一个错得离谱的提醒。
     */
    internal fun parseModelJson(
        raw: String,
        now: LocalDateTime,
        zone: ZoneId,
        originalText: String,
    ): EventIntentParser.Intent? {
        val json = extractJson(raw) ?: return null
        val title = json.optString("title").trim().take(MAX_TITLE_CHARS)
            .ifBlank { originalText.trim().take(MAX_TITLE_CHARS) }

        val dateText = json.optString("date").trim().takeIf { it.isNotBlank() && it != "null" }
        val day = dateText?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            ?: return null

        // 过去太久的日期几乎一定是模型算错，不接受。
        if (day.isBefore(now.toLocalDate().minusDays(1))) return null
        if (day.isAfter(now.toLocalDate().plusYears(2))) return null

        val allDay = json.optBoolean("allDay", false)
        val timeText = json.optString("time").trim().takeIf { it.isNotBlank() && it != "null" }
        val time = if (allDay) null else timeText?.let { parseTime(it) }

        val repeat = json.optString("repeat").trim().takeIf { it in RepeatRule.ALL } ?: RepeatRule.NONE
        val offset = json.optInt("reminderOffsetMinutes", if (allDay) 9 * 60 else 60)
            .coerceIn(EventEntity.NO_REMINDER, 30 * 24 * 60)

        return EventIntentParser.Intent(
            title = title,
            day = day,
            time = time,
            allDay = allDay || time == null,
            repeat = repeat,
            reminderOffsetMin = offset,
            matchedDateText = dateText,
            matchedTimeText = timeText.orEmpty(),
        )
    }

    /** 模型有时会把 JSON 包在 ```json 里或前后带解释文字。 */
    private fun extractJson(raw: String): JSONObject? {
        val text = raw.trim()
        Regex("\\{[\\s\\S]*}").find(text)?.let { match ->
            runCatching { JSONObject(match.value) }.getOrNull()?.let { return it }
        }
        return runCatching { JSONObject(text) }.getOrNull()
    }

    /** 接受 "8:30" / "08:30" / "8:30:00"。 */
    private fun parseTime(raw: String): LocalTime? {
        val m = Regex("(\\d{1,2})\\s*[:：]\\s*(\\d{1,2})").find(raw) ?: return null
        val hour = m.groupValues[1].toIntOrNull() ?: return null
        val minute = m.groupValues[2].toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        return LocalTime.of(hour, minute)
    }

    /**
     * 把解析结果落成事件实体。
     *
     * 抽成函数是为了让「本地解析」与「AI 解析」走同一条落库路径——
     * 两条路径各写一遍，迟早会出现「手动安排有时间、AI 安排没时间」这类不一致。
     */
    fun toEntity(intent: EventIntentParser.Intent, zone: ZoneId = ZoneId.systemDefault()): EventEntity? {
        val day = intent.day ?: return null
        val startAt = if (intent.allDay || intent.time == null) {
            day.atStartOfDay(zone).toInstant().toEpochMilli()
        } else {
            day.atTime(intent.time).atZone(zone).toInstant().toEpochMilli()
        }
        return EventEntity(
            title = intent.title.ifBlank { "新安排" },
            startAt = startAt,
            allDay = intent.allDay || intent.time == null,
            repeatRule = intent.repeat,
            reminderOffsetMin = intent.reminderOffsetMin,
        )
    }
}
