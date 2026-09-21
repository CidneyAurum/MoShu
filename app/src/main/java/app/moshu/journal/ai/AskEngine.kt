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
        /** 列表身份标识。同一句话可以问两次，因此不能用文本做 key。 */
        val id: Long = 0L,
    ) {
        val sourceCount: Int get() = sourceEntryIds.size
    }

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
            // 关键词会直接拼进 LIKE 模式串，通配符必须转义。
            addAll(dao.searchOnce(escapeLikePattern(keyword), 20))
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

    /** 与 EntryDao 的 LIKE ... ESCAPE '!' 配套：转义通配符，否则关键词里的 % 会匹配全部。 */
    private fun escapeLikePattern(value: String): String = value
        .replace("!", "!!")
        .replace("%", "!%")
        .replace("_", "!_")
}
