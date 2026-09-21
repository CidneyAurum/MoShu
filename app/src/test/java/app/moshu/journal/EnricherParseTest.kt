package app.moshu.journal

import app.moshu.journal.ai.Enricher
import app.moshu.journal.data.db.Category
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 整理引擎的解析与取值收敛：不依赖网络。 */
class EnricherParseTest {

    @Test
    fun `未知情绪值收敛为 neutral`() {
        // 模型偶尔会回 happy / sad 这类同义写法，落库前必须收敛，
        // 否则条目在回顾页没有表情、也不计入平均心情。
        assertEquals("neutral", Enricher.normalizeMood("happy"))
        assertEquals("neutral", Enricher.normalizeMood("SAD"))
        assertEquals("neutral", Enricher.normalizeMood(""))
        assertEquals("neutral", Enricher.normalizeMood("有点低落"))
        assertEquals("great", Enricher.normalizeMood("GREAT"))
        assertEquals("low", Enricher.normalizeMood(" low "))
    }

    @Test
    fun `解析正常 JSON 并归类到工作`() {
        val raw = """
            {"category":"work","tags":["排期","登录重构"],"summary":"和产品组确认了新版本排期",
             "mood":"good","todos":[{"text":"补接口文档","due":"2026-09-25"}]}
        """.trimIndent()
        val result = Enricher.parse(raw)
        assertNotNull(result)
        assertEquals(Category.WORK, result!!.categoryId)
        assertEquals(listOf("排期", "登录重构"), result.tags)
        assertEquals("good", result.mood)
        assertEquals(1, result.todos.size)
        assertEquals("补接口文档", result.todos.first().text)
        assertEquals("2026-09-25", result.todos.first().due)
    }

    @Test
    fun `情绪字段缺失或非法时仍产出可用结果`() {
        val result = Enricher.parse("""{"category":"idea","tags":["点子"],"summary":"一个想法","mood":"excited"}""")
        assertNotNull(result)
        assertEquals(Category.IDEA, result!!.categoryId)
        assertEquals("neutral", result.mood)
        assertTrue(result.todos.isEmpty())
    }

    @Test
    fun `剥离 markdown 代码块包裹`() {
        val raw = "```json\n{\"category\":\"life\",\"tags\":[],\"summary\":\"吃了面\",\"mood\":\"neutral\"}\n```"
        val result = Enricher.parse(raw)
        assertNotNull(result)
        assertEquals(Category.LIFE, result!!.categoryId)
    }

    @Test
    fun `没有 JSON 时返回 null 而不是抛异常`() {
        assertNull(Enricher.parse("抱歉，我无法完成这个请求。"))
        assertNull(Enricher.parse(""))
    }
}
