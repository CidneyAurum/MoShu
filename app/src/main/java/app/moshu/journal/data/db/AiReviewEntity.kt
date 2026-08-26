package app.moshu.journal.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "ai_reviews")
data class AiReviewEntity(
    @PrimaryKey val periodKey: String,
    val periodType: String,
    val content: String,
    val sourceUidsJson: String = "[]",
    val generatedAt: Long = System.currentTimeMillis(),
)
