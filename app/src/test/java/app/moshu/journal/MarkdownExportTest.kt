package app.moshu.journal

import app.moshu.journal.data.db.EntryEntity
import app.moshu.journal.data.export.MarkdownExport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * Markdown 导出。
 *
 * 这里盯的是两类事：标签这类从库里读出来的字段格式对不对（历史上把 JSON 数组按逗号切过），
 * 以及批量导出别把条目数、顺序、目录弄丢。
 */
class MarkdownExportTest {

    private fun entry(
        content: String,
        day: LocalDate,
        summary: String = "",
        tags: List<String> = emptyList(),
        mood: String = "",
        category: Int = 0,
    ) = EntryEntity(
        content = content,
        summary = summary,
        tagsJson = tags.joinToString(",", "[", "]") { "\"$it\"" },
        mood = mood,
        categoryId = category,
        createdAt = day.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(),
    )

    @Test
    fun `标签从 JSON 数组读出而不是按逗号切`() {
        val text = MarkdownExport.single(entry("正文", LocalDate.of(2026, 9, 24), tags = listOf("工作", "复盘")))
        assertTrue(text, text.contains("- 标签：#工作 #复盘"))
        assertFalse("标签行不该残留引号或方括号：$text", text.contains("[\""))
    }

    @Test
    fun `单个条目的 markdown 含标题 元信息与正文`() {
        val text = MarkdownExport.single(
            entry("今天读完了那本书", LocalDate.of(2026, 9, 24), summary = "读书", mood = "great", category = 1),
        )
        assertTrue(text, text.startsWith("# 读书"))
        assertTrue(text, text.contains("- 时间：2026年9月24日"))
        assertTrue(text, text.contains("- 分类：${app.moshu.journal.data.db.Category.NAMES[1]}"))
        assertTrue(text, text.contains("- 情绪：✨ 很好"))
        assertTrue(text, text.contains("> 读书"))
        assertTrue(text, text.trimEnd().endsWith("今天读完了那本书"))
    }

    @Test
    fun `批量导出带目录且顺序与传入一致`() {
        val first = entry("第一条", LocalDate.of(2026, 9, 22))
        val second = entry("第二条", LocalDate.of(2026, 9, 24))
        val text = MarkdownExport.bundle(listOf(first, second))
        assertTrue(text, text.contains("共 2 条"))
        assertTrue(text, text.contains("## 目录"))
        assertTrue(text, text.contains("1. 第一条"))
        assertTrue(text, text.contains("2. 第二条"))
        assertTrue(text, text.contains("## 1. 第一条"))
        assertTrue(text, text.contains("## 2. 第二条"))
        assertTrue("正文顺序应保持传入顺序", text.indexOf("## 1. 第一条") < text.indexOf("## 2. 第二条"))
    }

    @Test
    fun `空列表不产出内容`() {
        assertEquals("", MarkdownExport.bundle(emptyList()))
    }

    @Test
    fun `批量文件名带日期范围`() {
        val a = entry("a", LocalDate.of(2026, 9, 1))
        val b = entry("b", LocalDate.of(2026, 9, 24))
        assertEquals("墨枢导出-20260901-20260924.md", MarkdownExport.bundleFileName(listOf(a, b)))
        // 条目顺序通常是「最新在前」，所以范围要取两端极值而不是首尾。
        assertEquals("墨枢导出-20260901-20260924.md", MarkdownExport.bundleFileName(listOf(b, a)))
        // 同一天只写一次日期，不写成 20260924-20260924。
        assertEquals("墨枢导出-20260924.md", MarkdownExport.bundleFileName(listOf(b, b)))
    }

    @Test
    fun `标题优先用概括 没有概括才用正文首行`() {
        val withSummary = entry("正文首行", LocalDate.of(2026, 9, 24), summary = "一句话概括")
        assertEquals("一句话概括", MarkdownExport.titleForFile(withSummary))
        val noSummary = entry("正文首行\n第二行", LocalDate.of(2026, 9, 24))
        assertEquals("正文首行", MarkdownExport.titleForFile(noSummary))
    }

    @Test
    fun `文件名剔除非法字符并限长`() {
        val long = "读书/笔记：关于「A:B」的*思考*？" + "很长很长".repeat(20)
        val name = MarkdownExport.titleForFile(entry(long, LocalDate.of(2026, 9, 24)))
        assertFalse(name, name.any { it in "\\/:*?\"<>|" })
        assertTrue(name, name.length <= 30)
    }

    @Test
    fun `正文为空时文件名退回时间戳`() {
        val name = MarkdownExport.titleForFile(entry("   ", LocalDate.of(2026, 9, 24)))
        assertTrue(name, name.startsWith("记忆-20260924"))
    }

    @Test
    fun `导出内容不含密钥字段`() {
        val text = MarkdownExport.bundle(listOf(entry("正文", LocalDate.of(2026, 9, 24))))
        assertFalse(text, text.contains("sk-", ignoreCase = true))
        assertFalse(text, text.contains("apiKey", ignoreCase = true))
        assertFalse(text, text.contains("http"))
    }
}