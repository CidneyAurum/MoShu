package app.moshu.journal

import app.moshu.journal.ai.Enricher
import app.moshu.journal.data.db.Category
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EnricherTest {

    @Test
    fun `解析标准 JSON`() {
        val raw = """{"category":"work","tags":["加班","项目"],"summary":"项目上线加班到深夜","mood":"low"}"""
        val e = Enricher.parse(raw)!!
        assertEquals(Category.WORK, e.categoryId)
        assertEquals(listOf("加班", "项目"), e.tags)
        assertEquals("项目上线加班到深夜", e.summary)
        assertEquals("low", e.mood)
    }

    @Test
    fun `容忍 markdown 代码块包裹`() {
        val raw = "好的，以下是结果：\n```json\n{\"category\":\"idea\",\"tags\":[\"点子\"],\"summary\":\"一个App创意\",\"mood\":\"good\"}\n```"
        val e = Enricher.parse(raw)!!
        assertEquals(Category.IDEA, e.categoryId)
        assertEquals("good", e.mood)
    }

    @Test
    fun `非法输入返回 null`() {
        assertNull(Enricher.parse("这不是JSON"))
        assertNull(Enricher.parse(""))
    }

    @Test
    fun `未知分类回退为生活`() {
        val raw = """{"category":"whatever","tags":[],"summary":"x","mood":"neutral"}"""
        assertEquals(Category.LIFE, Enricher.parse(raw)!!.categoryId)
    }

    @Test
    fun `提取待办与截止日期`() {
        val raw = """{"category":"work","tags":["项目"],"summary":"x","mood":"neutral",
            "todos":[{"text":"周五交周报","due":"2026-08-28"},{"text":"","due":null}]}"""
        val todos = Enricher.parse(raw)!!.todos
        assertEquals(1, todos.size)
        assertEquals("周五交周报", todos[0].text)
        assertEquals("2026-08-28", todos[0].due)
    }

    @Test
    fun `没有待办时返回空列表`() {
        val raw = """{"category":"life","tags":[],"summary":"x","mood":"good","todos":[]}"""
        assertEquals(0, Enricher.parse(raw)!!.todos.size)
    }
}
