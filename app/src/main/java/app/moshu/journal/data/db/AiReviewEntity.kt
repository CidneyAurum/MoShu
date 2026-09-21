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
    /** 生成它的模型，用于说明出处，并在换模型后提示可以重新生成。 */
    val model: String = "",
    val promptVersion: Int = 0,
    /** 实际纳入生成的记录条数（可能少于当月总数）。 */
    val entryCount: Int = 0,
)
