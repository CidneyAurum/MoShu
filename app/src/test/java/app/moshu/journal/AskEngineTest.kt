package app.moshu.journal

import app.moshu.journal.ai.AskEngine
import org.junit.Assert.assertEquals
import org.junit.Test

class AskEngineTest {

    @Test
    fun `中英混合提问抽取关键词`() {
        val keywords = AskEngine.extractKeywords("上周说的学 Rust 和健身计划怎么样了")
        assertEquals(listOf("上周说的学", "rust", "和健身计划怎么样了"), keywords)
    }

    @Test
    fun `短词被过滤`() {
        val keywords = AskEngine.extractKeywords("a 我 ok 今天")
        assertEquals(listOf<String>(), keywords.filter { it.length < 2 })
    }

    @Test
    fun `去重且最多八个`() {
        val keywords = AskEngine.extractKeywords("工作 工作 工作 健身 健身 阅读 电影 音乐 旅行 学习 代码")
        assertEquals(keywords.size, keywords.distinct().size)
        assert(keywords.size <= 8)
    }
}
