package app.moshu.journal.ai

import app.moshu.journal.data.db.EntryDao
import app.moshu.journal.data.db.EntryEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * M4 对话式回顾（RAG）。
 * 检索策略：问题关键词 LIKE 命中 + 近 14 天全部条目兜底，拼成摘录交给 LLM 作答。
 * （向量检索留待接入支持 embeddings 的服务商后升级）
 */
object AskEngine {

    data class ChatMessage(
        val role: String, // "user" | "assistant"
        val text: String,
        val sourceEntryIds: List<Long> = emptyList(),
    ) {
        val sourceCount: Int get() = sourceEntryIds.size
    }

    data class AskAnswer(val text: String, val sourceEntryIds: List<Long>)

    val SYSTEM_PROMPT = """
你是「墨枢」，用户的私人日记助手。仅依据给出的日记摘录回答用户的问题；摘录不足以回答时请直说不知道。
用中文回答，口语化、简洁；引用日记内容时带上日期，例如「8月20日你提到过…」。
""".trim()

    /** 从提问中抽取检索关键词：英文/数字词(≥2位) + 中文连续串(≥2字)，按原文顺序 */
    fun extractKeywords(question: String): List<String> {
        val pattern = Regex("[A-Za-z0-9]{2,}|[\\u4e00-\\u9fa5]{2,}")
        return pattern.findAll(question).map { match ->
            val value = match.value
            if (value.all { it.code < 128 }) value.lowercase() else value
        }.distinct().take(8).toList()
    }

    suspend fun retrieve(dao: EntryDao, question: String, now: Long): List<EntryEntity> {
        val seen = mutableSetOf<Long>()
        val result = mutableListOf<EntryEntity>()

        fun addAll(items: List<EntryEntity>) {
            for (item in items) {
                if (seen.add(item.id)) result.add(item)
            }
        }

        for (keyword in extractKeywords(question)) {
            if (result.size >= 40) break
            addAll(dao.searchOnce(keyword, 20))
        }
        // 近两周条目永远参与，保证"最近在忙什么"这类泛问也能答
        addAll(dao.since(now - 14L * 24 * 60 * 60 * 1000).take(30))
        return result.sortedByDescending { it.createdAt }.take(40)
    }

    fun buildUserPrompt(excerpts: List<EntryEntity>): String {
        val dayFormat = SimpleDateFormat("M月d日", Locale.CHINA)
        val body = excerpts.joinToString(separator = "\n") {
            "${dayFormat.format(Date(it.createdAt))}：${it.content.take(150)}"
        }
        return "【日记摘录】\n$body"
    }

    suspend fun ask(
        config: AiConfig,
        dao: EntryDao,
        question: String,
        now: Long,
    ): Pair<AiClient.Result, Int> {
        val excerpts = retrieve(dao, question, now)
        val userPrompt = buildUserPrompt(excerpts) + "\n\n【问题】$question"
        val result = AiClient.complete(config, SYSTEM_PROMPT, userPrompt, temperature = 0.5)
        return result to excerpts.size
    }
}
