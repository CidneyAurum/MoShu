package app.moshu.journal

import app.moshu.journal.ai.Enricher
import app.moshu.journal.data.db.Category
import app.moshu.journal.data.db.EntryEntity
import app.moshu.journal.data.db.ManualMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

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

    @Test
    fun `分类别名收敛到对应分类`() {
        // 只对真正无法识别的值回退 LIFE；job/职场/创意 这类别名不能静默归错。
        assertEquals(Category.WORK, Enricher.categoryIdOf("job"))
        assertEquals(Category.WORK, Enricher.categoryIdOf("职场"))
        assertEquals(Category.IDEA, Enricher.categoryIdOf("创意"))
        assertEquals(Category.IDEA, Enricher.categoryIdOf("Idea"))
        assertEquals(Category.LIFE, Enricher.categoryIdOf("health"))
        assertEquals(Category.LIFE, Enricher.categoryIdOf("家人"))
        assertEquals(Category.LIFE, Enricher.categoryIdOf("什么鬼"))
        assertEquals(Category.LIFE, Enricher.categoryIdOf(""))
    }

    @Test
    fun `标签去标点去重去空泛词并限长`() {
        val raw = """
            {"category":"work","tags":["#加班","加班","「生活」","超长标签超过八个字","健身"],
             "summary":"x","mood":"neutral"}
        """.trimIndent()
        assertEquals(listOf("加班", "健身"), Enricher.parse(raw)!!.tags)
    }

    @Test
    fun `标签最多四个且大小写去重`() {
        val raw = """{"category":"idea","tags":["A","a","一","二","三","四"],"summary":"x","mood":"neutral"}"""
        assertEquals(listOf("A", "一", "二", "三"), Enricher.parse(raw)!!.tags)
    }

    @Test
    fun `概括与待办按契约截断`() {
        val longSummary = "一".repeat(40)
        val longTodo = "二".repeat(60)
        val raw = """{"category":"life","tags":[],"summary":"$longSummary","mood":"neutral",
            "todos":[{"text":"$longTodo"}]}"""
        val result = Enricher.parse(raw)!!
        assertEquals(24, result.summary.length)
        assertEquals(40, result.todos.first().text.length)
    }

    @Test
    fun `changedFields 只标出真正变动的字段`() {
        val original = EntryEntity(
            id = 1,
            content = "原文",
            categoryId = Category.LIFE,
            tagsJson = "[\"加班\"]",
            summary = "旧概括",
            mood = "neutral",
        )
        assertEquals(
            0,
            Enricher.changedFields(original, "原文", Category.LIFE, listOf("加班"), "neutral", "旧概括"),
        )
        assertEquals(
            ManualMetadata.CATEGORY,
            Enricher.changedFields(original, "原文", Category.WORK, listOf("加班"), "neutral", "旧概括"),
        )
        assertEquals(
            ManualMetadata.TAGS,
            Enricher.changedFields(original, "原文", Category.LIFE, listOf("加班", "健身"), "neutral", "旧概括"),
        )
        assertEquals(
            ManualMetadata.MOOD or ManualMetadata.SUMMARY,
            Enricher.changedFields(original, "原文", Category.LIFE, listOf("加班"), "low", "新概括"),
        )
    }

    @Test
    fun `changedFields 比较前先做标签收敛`() {
        val original = EntryEntity(id = 1, content = "x", tagsJson = "[\"加班\"]")
        // 「#加班」收敛后等于「加班」，不应因为写法差异误判为用户改过标签。
        assertEquals(0, Enricher.changedFields(original, "x", Category.LIFE, listOf("#加班"), "", ""))
    }

    @Test
    fun `parseDue 接受多种日期写法并归一化`() {
        val today = LocalDate.of(2026, 9, 21)
        val target = LocalDate.of(2026, 9, 25).toEpochDay().toInt()
        assertEquals(target, Enricher.parseDue("2026-09-25", today))
        assertEquals(target, Enricher.parseDue("2026/9/25", today))
        assertEquals(target, Enricher.parseDue("2026-9-25", today))
        assertEquals(target, Enricher.parseDue("9月25日", today))
        assertEquals(target, Enricher.parseDue("9-25", today))
        assertNull(Enricher.parseDue("下周五", today))
        assertNull(Enricher.parseDue(null, today))
        assertNull(Enricher.parseDue("", today))
    }

    @Test
    fun `parseDue 无年份且已过去时顺延到明年`() {
        val today = LocalDate.of(2026, 9, 21)
        assertEquals(
            LocalDate.of(2027, 1, 5).toEpochDay().toInt(),
            Enricher.parseDue("1月5日", today),
        )
        // 就是今天则保留今年。
        assertEquals(
            LocalDate.of(2026, 9, 21).toEpochDay().toInt(),
            Enricher.parseDue("9月21日", today),
        )
    }

    @Test
    fun `topTags 按词频排序`() {
        val tags = Enricher.topTags(listOf("[\"健身\",\"加班\"]", "[\"健身\"]", "[\"健身\",\"阅读\"]"))
        assertEquals(listOf("健身", "加班", "阅读"), tags)
    }
}