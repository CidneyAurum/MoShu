package app.moshu.journal

import app.moshu.journal.ai.AskEngine
import app.moshu.journal.data.db.EntryEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class AskEngineTest {

    @Test
    fun `中文按二字组切分并保留英文词`() {
        // 整段连续汉字做 LIKE 永远匹配不到任何一条记录，必须拆成二字组。
        val keywords = AskEngine.extractKeywords("上周说的学 Rust 和健身计划怎么样了")
        assertTrue(keywords.contains("健身"))
        assertTrue(keywords.contains("计划"))
        assertTrue(keywords.contains("rust"))
        // 中文关键词一律是二字组，不再出现「上周说的学」这种整段。
        assertTrue(keywords.filter { it.any { char -> char.code in 0x4e00..0x9fa5 } }.all { it.length == 2 })
        assertTrue(keywords.none { it == "怎么" || it == "怎么样" })
    }

    @Test
    fun `停用词被过滤`() {
        val keywords = AskEngine.extractKeywords("最近怎么样")
        assertTrue(keywords.none { it == "最近" || it == "怎么" || it == "怎么样" })
    }

    @Test
    fun `短词被过滤`() {
        val keywords = AskEngine.extractKeywords("a 我 ok 今天")
        assertEquals(listOf("ok", "今天"), keywords)
    }

    @Test
    fun `去重且最多十二个`() {
        val keywords = AskEngine.extractKeywords("工作 工作 工作 健身 健身 阅读 电影 音乐 旅行 学习 代码")
        assertEquals(keywords.size, keywords.distinct().size)
        assertTrue(keywords.size <= 12)
    }

    @Test
    fun `上周解析为上一个完整自然周`() {
        val today = LocalDate.of(2026, 9, 21) // 周一
        val range = AskEngine.parseDateRange("上周都干了什么", today)!!
        assertEquals(epochMillis(2026, 9, 14), range.start)
        assertEquals(epochMillis(2026, 9, 21), range.end)
        assertEquals("9月14日–9月20日", range.label)
    }

    @Test
    fun `去年与显式年份解析为整年`() {
        val today = LocalDate.of(2026, 9, 21)
        val lastYear = AskEngine.parseDateRange("去年读了多少书", today)!!
        assertEquals(epochMillis(2025, 1, 1), lastYear.start)
        assertEquals(epochMillis(2026, 1, 1), lastYear.end)
        val explicit = AskEngine.parseDateRange("2024年我都在忙什么", today)!!
        assertEquals(epochMillis(2024, 1, 1), explicit.start)
        assertTrue(explicit.label.contains("2024年1月1日"))
    }

    @Test
    fun `没有时间表达时返回 null`() {
        assertEquals(null, AskEngine.parseDateRange("健身计划怎么样了", LocalDate.of(2026, 9, 21)))
    }

    @Test
    fun `citedIndices 提取方括号编号并去重`() {
        assertEquals(
            listOf(1, 3, 12),
            AskEngine.citedIndices("[1] 你提到健身 [3]，还有[3]和[12]。"),
        )
        assertEquals(emptyList<Int>(), AskEngine.citedIndices("没有引用"))
    }

    @Test
    fun `buildPrompt 为摘录编号并附标签`() {
        val entry = EntryEntity(
            id = 7,
            content = "今天去健身房练了背",
            tagsJson = "[\"健身\"]",
            summary = "健身的一天",
        )
        val prompt = AskEngine.buildPrompt(listOf(AskEngine.Excerpt(entry, 3, nearContext = false)), "上周健身了吗")
        assertTrue(prompt.contains("[1]"))
        assertTrue(prompt.contains("标签：健身"))
        assertTrue(prompt.contains("健身的一天"))
    }

    private fun epochMillis(year: Int, month: Int, day: Int): Long =
        LocalDate.of(year, month, day).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
}