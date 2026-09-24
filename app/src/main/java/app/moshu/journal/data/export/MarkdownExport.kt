package app.moshu.journal.data.export

import app.moshu.journal.data.db.Category
import app.moshu.journal.data.db.EntryEntity
import app.moshu.journal.ui.components.moodLabel
import app.moshu.journal.ui.components.parseTags
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Markdown 导出。单条导出和批量导出共用同一份正文排版，
 * 否则「详情页导出的文件」和「批量导出的同一篇」会长得不一样。
 *
 * 导出内容只来自用户自己写的东西（正文、概括、标签、分类、情绪），
 * 不含任何密钥、服务地址或提示词。
 */
object MarkdownExport {

    /** 导出文件名：用正文首行当标题，去掉不能出现在文件名里的字符。 */
    fun titleForFile(entry: EntryEntity): String {
        val raw = entry.summary.ifBlank { entry.content }.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
        val cleaned = raw.replace(Regex("[\\\\/:*?\"<>|\\n\\r]"), " ").trim().take(30)
        return cleaned.ifBlank { "记忆-${timestamp("yyyyMMdd-HHmm", entry.createdAt)}" }
    }

    /** 单条 Markdown：概括 + 正文 + 元信息。 */
    fun single(entry: EntryEntity): String = buildString {
        appendLine("# ${titleForFile(entry)}")
        appendLine()
        append(metaLines(entry))
        appendLine()
        appendLine(entry.content)
    }

    /**
     * 批量导出成一个文件：开头是目录，后面按时间顺序排列正文。
     *
     * 不用「一个条目一个文件」的 zip：手机上拿到一个压缩包还得先解压才能看，
     * 而这份导出最常见的用途是丢进笔记软件或发给别人，单个 md 更顺手。
     */
    fun bundle(entries: List<EntryEntity>): String {
        if (entries.isEmpty()) return ""
        val stamp = SimpleDateFormat("yyyy年M月d日 HH:mm", Locale.CHINA).format(Date())
        return buildString {
            appendLine("# 墨枢导出")
            appendLine()
            appendLine("导出时间：$stamp")
            appendLine("共 ${entries.size} 条")
            appendLine()
            appendLine("## 目录")
            appendLine()
            entries.forEachIndexed { index, entry ->
                appendLine("${index + 1}. ${titleForFile(entry)} · ${timestamp("yyyy-MM-dd HH:mm", entry.createdAt)}")
            }
            entries.forEachIndexed { index, entry ->
                appendLine()
                appendLine("---")
                appendLine()
                appendLine("## ${index + 1}. ${titleForFile(entry)}")
                appendLine()
                append(metaLines(entry))
                appendLine()
                appendLine(entry.content)
            }
        }
    }

    /** 批量导出的文件名：带上日期范围，方便在文件管理器里区分几次导出。 */
    fun bundleFileName(entries: List<EntryEntity>): String {
        if (entries.isEmpty()) return "墨枢导出.md"
        // 条目顺序可能是「最新在前」，所以两端各自取极值而不是取首尾。
        val oldest = entries.minOf { it.createdAt }
        val newest = entries.maxOf { it.createdAt }
        val from = timestamp("yyyyMMdd", oldest)
        val to = timestamp("yyyyMMdd", newest)
        return if (from == to) "墨枢导出-$from.md" else "墨枢导出-$from-$to.md"
    }

    private fun metaLines(entry: EntryEntity): String = buildString {
        appendLine("- 时间：${timestamp("yyyy年M月d日 HH:mm", entry.createdAt)}")
        appendLine("- 分类：${Category.NAMES.getOrElse(entry.categoryId) { "未分类" }}")
        if (entry.mood.isNotBlank()) appendLine("- 情绪：${moodLabel(entry.mood)}")
        val tags = storedTags(entry.tagsJson)
        if (tags.isNotEmpty()) appendLine("- 标签：${tags.joinToString(" ") { "#$it" }}")
        if (entry.summary.isNotBlank()) {
            appendLine()
            appendLine("> ${entry.summary}")
        }
    }

    private fun timestamp(pattern: String, millis: Long): String =
        SimpleDateFormat(pattern, Locale.CHINA).format(Date(millis))
}

/**
 * 读出条目上的标签。
 *
 * 库里存的是 JSON 数组（`["a","b"]`），不能用逗号切：
 * 早先导出走的是逗号切分，于是 `["a","b"]` 被切成 `["a"` 和 `"b"]` 两个带引号的假标签。
 */
fun storedTags(tagsJson: String): List<String> =
    parseTags(tagsJson).map { it.trim() }.filter { it.isNotBlank() }.distinct().take(6)