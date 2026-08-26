package app.moshu.journal.data.db

import androidx.room.Entity
import androidx.room.ColumnInfo
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "entries", indices = [Index(value = ["uid"], unique = true)])
data class EntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(defaultValue = "''") val uid: String = UUID.randomUUID().toString(),
    val content: String,
    // 0=生活 1=工作 2=灵感（见 Category）
    val categoryId: Int = Category.LIFE,
    // JSON 数组字符串，如 ["加班","健身"]
    val tagsJson: String = "[]",
    // AI 生成的概括
    val summary: String = "",
    // great|good|neutral|low|bad
    val mood: String = "",
    val enriched: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = createdAt,
    @ColumnInfo(defaultValue = "0") val isPinned: Boolean = false,
    @ColumnInfo(defaultValue = "'idle'") val aiState: String = EntryAiState.IDLE.value,
    @ColumnInfo(defaultValue = "''") val aiError: String = "",
    @ColumnInfo(defaultValue = "0") val manualMetadataMask: Int = 0,
)

object ManualMetadata {
    const val CATEGORY = 1
    const val TAGS = 1 shl 1
    const val MOOD = 1 shl 2
    const val SUMMARY = 1 shl 3

    fun isManual(mask: Int, field: Int): Boolean = mask and field != 0
}
