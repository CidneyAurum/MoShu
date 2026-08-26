package app.moshu.journal

import app.moshu.journal.data.db.EntryEntity
import app.moshu.journal.ui.insights.InsightsViewModel
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class InsightsStreakTest {
    private fun entry(day: LocalDate) = EntryEntity(
        content = day.toString(),
        createdAt = day.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(),
    )

    @Test
    fun `今天有记录时连续计算到今天`() {
        val today = LocalDate.of(2026, 8, 26)
        val entries = listOf(entry(today), entry(today.minusDays(1)), entry(today.minusDays(2)), entry(today.minusDays(4)))
        assertEquals(3, InsightsViewModel.calculateStreak(entries, today))
    }

    @Test
    fun `今天未写时允许从昨天计算`() {
        val today = LocalDate.of(2026, 8, 26)
        assertEquals(2, InsightsViewModel.calculateStreak(listOf(entry(today.minusDays(1)), entry(today.minusDays(2))), today))
    }
}
