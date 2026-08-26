package app.moshu.journal.ai

import app.moshu.journal.data.db.Category
import org.json.JSONArray
import org.json.JSONObject

/**
 * 条目整理引擎：把随手记的一段话交给 LLM，
 * 归类（生活/工作/灵感）、打标签、生成一句话概括、判断情绪。
 */
object Enricher {

    private val SYSTEM_PROMPT = """
你是「墨枢」的日志整理引擎。用户给你一段随手记下的文字，请输出严格的 JSON（禁止 markdown 代码块），格式：
{"category":"life|work|idea","tags":["标签"],"summary":"不超过20字的概括","mood":"great|good|neutral|low|bad","todos":[{"text":"待办内容","due":"YYYY-MM-DD"}]}

规则：
- category：日常琐事/饮食/健康/家人朋友/消费=life；任务/会议/学习/职业发展=work；点子/创作/脑洞/计划=idea
- tags：提取 1~4 个具体短标签，如「加班」「健身」「电影」
- summary：用第三人称概括这条记录的核心信息
- mood：依据整体情绪判断，没有明显情绪时用 neutral
- todos：只提取文中明确承诺或计划要做的事；没有则为空数组 []；due 仅当文中出现明确日期时给出，否则省略该字段
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
            val category = when (obj.optString("category")) {
                "work" -> Category.WORK
                "idea" -> Category.IDEA
                else -> Category.LIFE
            }
            val tags = buildList {
                val arr = obj.optJSONArray("tags") ?: JSONArray()
                for (i in 0 until arr.length()) add(arr.getString(i))
            }.filter { it.isNotBlank() }.take(6)

            val todos = buildList {
                val arr = obj.optJSONArray("todos") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val t = arr.optJSONObject(i) ?: continue
                    val text = t.optString("text").trim()
                    if (text.isNotEmpty()) add(TodoDraft(text.take(80), t.optString("due", "").ifBlank { null }))
                }
            }.take(10)

            Enrichment(
                categoryId = category,
                tags = tags,
                summary = obj.optString("summary").take(60),
                mood = obj.optString("mood").take(10),
                todos = todos,
            )
        }
    } catch (_: Exception) {
        null
    }

    suspend fun enrich(
        config: AiConfig,
        content: String,
        images: List<AiClient.ImageInput> = emptyList(),
    ): Pair<AiClient.Result, Enrichment?> {
        val result = AiClient.complete(config, SYSTEM_PROMPT, content, temperature = 0.2, images = images)
        val enrichment = (result as? AiClient.Result.Ok)?.let { parse(it.text) }
        return result to enrichment
    }
}
