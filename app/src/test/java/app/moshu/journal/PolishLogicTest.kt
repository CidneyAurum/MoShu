package app.moshu.journal

import app.moshu.journal.data.backup.BackupManager
import app.moshu.journal.data.db.Category
import app.moshu.journal.data.db.EntryAiState
import app.moshu.journal.data.db.EntryEntity
import app.moshu.journal.data.db.TodoEntity
import app.moshu.journal.ui.components.shareEntryText
import app.moshu.journal.ui.record.EntrySort
import app.moshu.journal.ui.record.MemoryFilters
import app.moshu.journal.ui.record.sortEntries
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 本轮打磨新增逻辑的回归测试：排序、筛选判定、分享文本与备份字段往返。 */
class PolishLogicTest {

    private fun entry(id: Long, createdAt: Long, updatedAt: Long = createdAt, pinned: Boolean = false) =
        EntryEntity(id = id, content = "内容$id", createdAt = createdAt, updatedAt = updatedAt, isPinned = pinned)

    @Test
    fun `排序时置顶恒在最前`() {
        val list = listOf(entry(1, 100), entry(2, 300), entry(3, 200, pinned = true))
        assertEquals(listOf(3L, 2L, 1L), sortEntries(list, EntrySort.NEWEST).map { it.id })
        // 最早排序下，置顶项仍然优先于更早的非置顶项。
        assertEquals(listOf(3L, 1L, 2L), sortEntries(list, EntrySort.OLDEST).map { it.id })
    }

    @Test
    fun `按最近修改排序看的是 updatedAt`() {
        val list = listOf(entry(1, createdAt = 100, updatedAt = 500), entry(2, createdAt = 300, updatedAt = 200))
        assertEquals(listOf(1L, 2L), sortEntries(list, EntrySort.UPDATED).map { it.id })
        // 最新排序只看创建时间，两条顺序应当相反。
        assertEquals(listOf(2L, 1L), sortEntries(list, EntrySort.NEWEST).map { it.id })
    }

    @Test
    fun `只有排序变化时不算有筛选`() {
        assertFalse(MemoryFilters(sort = EntrySort.OLDEST).active)
        assertTrue(MemoryFilters(query = "加班").active)
        assertTrue(MemoryFilters(failedOnly = true).active)
        assertTrue(MemoryFilters(category = Category.WORK).active)
        assertTrue(MemoryFilters(mood = "low").active)
        assertTrue(MemoryFilters(pinnedOnly = true).active)
    }

    @Test
    fun `分享文本包含概括正文与标签`() {
        val value = EntryEntity(
            id = 1,
            content = "正文",
            summary = "概括",
            tagsJson = """["加班","排期"]""",
        )
        val text = shareEntryText(value)
        assertTrue(text.contains("概括"))
        assertTrue(text.contains("正文"))
        assertTrue(text.contains("#加班"))
        assertTrue(text.contains("#排期"))
    }

    @Test
    fun `分享文本在没有概括标签时只给正文`() {
        assertEquals("只有正文", shareEntryText(EntryEntity(id = 1, content = "只有正文")))
    }

    @Test
    fun `备份会带上模型与提示词版本`() {
        val original = EntryEntity(
            id = 1,
            uid = "uid-1",
            content = "正文",
            aiState = EntryAiState.SUCCEEDED.value,
            aiModel = "deepseek-flash",
            aiPromptVersion = 3,
        )
        val restored = BackupManager.parseEntry(BackupManager.entryJson(original), "uid-1")
        assertEquals("deepseek-flash", restored.aiModel)
        assertEquals(3, restored.aiPromptVersion)
        assertEquals(EntryAiState.SUCCEEDED.value, restored.aiState)
    }

    @Test
    fun `备份会带上 AI 建议标记`() {
        val suggested = TodoEntity(id = 1, uid = "t-1", text = "补文档", sourceEntryId = 5, createdAt = 1, isAiSuggested = true)
        val restored = BackupManager.parseTodo(BackupManager.todoJson(suggested, "uid-5"), "t-1", 7)
        assertTrue(restored.isAiSuggested)
        assertEquals(7L, restored.sourceEntryId)

        val manual = TodoEntity(id = 2, uid = "t-2", text = "自己建的", sourceEntryId = 0, createdAt = 1)
        assertFalse(BackupManager.parseTodo(BackupManager.todoJson(manual, null), "t-2", 0).isAiSuggested)
    }

    @Test
    fun `旧备份缺少新字段时回落到默认值`() {
        val legacy = JSONObject().apply {
            put("uid", "u")
            put("content", "老备份")
            put("aiState", EntryAiState.FAILED.value)
        }
        val restored = BackupManager.parseEntry(legacy, "u")
        assertEquals("", restored.aiModel)
        assertEquals(0, restored.aiPromptVersion)
        assertEquals(EntryAiState.FAILED.value, restored.aiState)
    }

    @Test
    fun `回顾出处字段可以往返`() {
        val json = JSONObject().apply {
            put("periodKey", "month-2026-09")
            put("periodType", "month")
            put("content", "本月主线")
            put("generatedAt", 123L)
            put("model", "deepseek-flash")
            put("promptVersion", 2)
            put("entryCount", 17)
        }
        val restored = BackupManager.parseReview(json)
        assertEquals("deepseek-flash", restored.model)
        assertEquals(2, restored.promptVersion)
        assertEquals(17, restored.entryCount)
    }

    @Test
    fun `失败通知的 extra 与待办通知不共用`() {
        // 两者共用会让失败通知把用户送到「行动」页，而失败记录只在「记忆」页。
        assertNotEquals(MainActivity.EXTRA_OPEN_ACTIONS, MainActivity.EXTRA_OPEN_AI_FAILURES)
        assertEquals("open_ai_failures", MainActivity.EXTRA_OPEN_AI_FAILURES)
    }
}