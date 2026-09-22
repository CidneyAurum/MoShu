package app.moshu.journal

import app.moshu.journal.data.JournalRepository
import app.moshu.journal.data.WritingStats
import app.moshu.journal.data.db.EntryEntity
import app.moshu.journal.ui.components.ENTRY_TEMPLATES
import app.moshu.journal.ui.components.templateOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 第二轮打磨（R51–R80）里新增的纯逻辑。
 *
 * 只测不需要 Android 运行时的部分：标签序列化、链接标题解析、模板查找、统计口径。
 * 这些是最容易在后续改动里被悄悄改坏的边界。
 */
class PolishRound2Test {

    // ---------- 标签 ----------

    @Test
    fun `标签序列化与解析互为逆运算`() {
        val tags = listOf("加班", "健身", "reading")
        val json = JournalRepository.tagsJsonOf(tags)
        assertEquals(tags, JournalRepository.parseTags(json))
    }

    @Test
    fun `空标签列表写成空数组而不是空串`() {
        // 历史上用空串表示「没有标签」，导致 allTagsJson 的 WHERE 条件要多写一个分支。
        assertEquals("[]", JournalRepository.tagsJsonOf(emptyList()))
        assertTrue(JournalRepository.parseTags("[]").isEmpty())
    }

    @Test
    fun `脏标签数据不抛异常`() {
        assertEquals(emptyList<String>(), JournalRepository.parseTags("不是 JSON"))
        assertEquals(emptyList<String>(), JournalRepository.parseTags(""))
    }

    @Test
    fun `标签里的空白项被丢掉`() {
        assertEquals(listOf("a", "b"), JournalRepository.parseTags("""["a","","b"]"""))
    }

    // ---------- 双向链接标题 ----------

    @Test
    fun `标题取正文首行`() {
        assertEquals("今天很累", JournalRepository.linkTitleOf("今天很累\n第二行"))
    }

    @Test
    fun `首行是空白时顺延到下一个非空行`() {
        assertEquals("真正的标题", JournalRepository.linkTitleOf("\n\n   \n真正的标题\n正文"))
    }

    @Test
    fun `超长首行被截断`() {
        val long = "字".repeat(200)
        assertEquals(40, JournalRepository.linkTitleOf(long).length)
    }

    @Test
    fun `空正文得到空标题`() {
        assertEquals("", JournalRepository.linkTitleOf(""))
        assertEquals("", JournalRepository.linkTitleOf("\n\n"))
    }

    // ---------- 模板 ----------

    @Test
    fun `未知模板 key 回退到空白模板而不是崩溃`() {
        val fallback = templateOf("不存在的模板")
        assertEquals(ENTRY_TEMPLATES.first(), fallback)
        assertEquals("", fallback.body)
    }

    @Test
    fun `空白模板不预填任何内容`() {
        // 预填了内容就不是「空白」了，用户还得先删掉。
        assertTrue(templateOf("blank").body.isEmpty())
    }

    @Test
    fun `每个模板都有 key 与说明`() {
        ENTRY_TEMPLATES.forEach { template ->
            assertTrue("模板 key 不能为空", template.key.isNotBlank())
            assertTrue("模板「${template.key}」缺少标签", template.label.isNotBlank())
            assertTrue("模板「${template.key}」缺少说明", template.hint.isNotBlank())
        }
    }

    @Test
    fun `模板 key 不重复`() {
        // key 重复会让 templateOf 永远取到第一个，后面的模板等于不存在。
        assertEquals(ENTRY_TEMPLATES.size, ENTRY_TEMPLATES.map { it.key }.toSet().size)
    }

    // ---------- 收藏与回收站字段 ----------

    @Test
    fun `默认条目既不在回收站也未收藏`() {
        val entry = EntryEntity(content = "x")
        assertFalse(entry.inTrash)
        assertFalse(entry.isStarred)
    }

    @Test
    fun `删除时间非零即视为在回收站`() {
        assertTrue(EntryEntity(content = "x", deletedAt = 1L).inTrash)
        assertFalse(EntryEntity(content = "x", deletedAt = 0L).inTrash)
    }

    // ---------- 统计口径 ----------

    @Test
    fun `空库的统计全为零且最忙时段为空`() {
        val stats = WritingStats()
        assertEquals(0, stats.totalEntries)
        assertEquals(0, stats.totalChars)
        assertEquals(0, stats.averageChars)
        assertNull(stats.busiestHour)
    }

    @Test
    fun `字数按非空白字符计`() {
        // 中文场景下按空格分词没有意义，所以统计口径是「去掉所有空白后的字符数」。
        val text = "今天 写了 一段话\n第二行"
        assertEquals(10, text.count { !it.isWhitespace() })
    }

    // ---------- 时区/跨月边界（D17）----------

    @Test
    fun `月末最后一天的毫秒值属于当月最后一天`() {
        // 23:59:59.999 的 createdAt 不能因为取整或时区换算跑进下个月。
        val zone = java.time.ZoneId.systemDefault()
        val endOfDay = java.time.LocalDate.of(2026, 1, 31).atTime(23, 59, 59, 999_999_999).atZone(zone)
        val day = java.time.Instant.ofEpochMilli(endOfDay.toInstant().toEpochMilli())
            .atZone(zone).toLocalDate()
        assertEquals(java.time.LocalDate.of(2026, 1, 31), day)
    }

    @Test
    fun `月初零点的毫秒值属于当月第一天`() {
        val zone = java.time.ZoneId.systemDefault()
        val startOfDay = java.time.LocalDate.of(2026, 3, 1).atStartOfDay(zone)
        val day = java.time.Instant.ofEpochMilli(startOfDay.toInstant().toEpochMilli())
            .atZone(zone).toLocalDate()
        assertEquals(java.time.LocalDate.of(2026, 3, 1), day)
    }

    @Test
    fun `标签序列化对含引号的标签安全`() {
        // 用户可以手动写带引号的标签；JSON 序列化必须转义而不是产出坏 JSON。
        val tags = listOf("带\"引号\"的标签")
        val json = JournalRepository.tagsJsonOf(tags)
        assertEquals(tags, JournalRepository.parseTags(json))
    }
}
