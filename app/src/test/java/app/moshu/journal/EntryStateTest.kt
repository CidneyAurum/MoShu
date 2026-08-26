package app.moshu.journal

import app.moshu.journal.data.db.EntryAiState
import app.moshu.journal.data.db.ManualMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EntryStateTest {
    @Test
    fun `未知 AI 状态安全回退`() {
        assertEquals(EntryAiState.IDLE, EntryAiState.from("future-state"))
    }

    @Test
    fun `人工元数据位互不覆盖`() {
        val mask = ManualMetadata.CATEGORY or ManualMetadata.MOOD
        assertTrue(ManualMetadata.isManual(mask, ManualMetadata.CATEGORY))
        assertTrue(ManualMetadata.isManual(mask, ManualMetadata.MOOD))
        assertFalse(ManualMetadata.isManual(mask, ManualMetadata.TAGS))
    }
}
