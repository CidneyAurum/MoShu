package app.moshu.journal

import app.moshu.journal.data.JournalRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 搜索路由的纯逻辑部分。
 *
 * FTS4 的默认分词器把连续 CJK 字符视为一个 token，所以「今天加班到十点」整句是一个词，
 * 搜「加班」用 MATCH 永远匹配不到（而且是静默返回空）。含 CJK 的查询必须改走 LIKE 子串匹配。
 */
class SearchRoutingTest {

    @Test
    fun `中文查询判定为 CJK`() {
        assertTrue(JournalRepository.containsCjk("加班"))
        assertTrue(JournalRepository.containsCjk("今天加班到十点"))
        assertTrue(JournalRepository.containsCjk("会议 notes"))
        assertTrue(JournalRepository.containsCjk("カタカナ"))
        assertTrue(JournalRepository.containsCjk("한국어"))
    }

    @Test
    fun `纯西文查询不走 CJK 路径`() {
        assertFalse(JournalRepository.containsCjk("meeting"))
        assertFalse(JournalRepository.containsCjk("release schedule"))
        assertFalse(JournalRepository.containsCjk("2026-09-21"))
        assertFalse(JournalRepository.containsCjk(""))
    }

    @Test
    fun `LIKE 通配符被转义`() {
        // 不转义的话，搜「%」会命中全部记录，搜「_」会命中任意单字。
        assertEquals("!%", JournalRepository.escapeLike("%"))
        assertEquals("!_", JournalRepository.escapeLike("_"))
        assertEquals("100!%", JournalRepository.escapeLike("100%"))
        assertEquals("a!_b", JournalRepository.escapeLike("a_b"))
        assertEquals("!!", JournalRepository.escapeLike("!"))
    }

    @Test
    fun `普通查询保持不变`() {
        assertEquals("加班", JournalRepository.escapeLike("加班"))
        assertEquals("release schedule", JournalRepository.escapeLike("release schedule"))
    }

    @Test
    fun `转义符自身先被转义避免二次解释`() {
        // 「!%」若不先转义 !，会被解释成「转义符 + 通配符」，语义就反了。
        assertEquals("!!!%", JournalRepository.escapeLike("!%"))
    }
}
