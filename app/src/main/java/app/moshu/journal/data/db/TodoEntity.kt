package app.moshu.journal.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.ColumnInfo
import java.util.UUID

/** 待办雷达：从日志中由 AI 提取的承诺/任务 */
@Entity(
    tableName = "todos",
    indices = [Index("sourceEntryId"), Index(value = ["uid"], unique = true)],
)
data class TodoEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(defaultValue = "''") val uid: String = UUID.randomUUID().toString(),
    val text: String,
    val sourceEntryId: Long,
    val createdAt: Long,
    // LocalDate.toEpochDay()，null=未设截止
    val dueEpochDay: Int? = null,
    val done: Boolean = false,
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = createdAt,
    val completedAt: Long? = null,
    val reminderAt: Long? = null,
    @ColumnInfo(defaultValue = "0") val isUserCreated: Boolean = false,
    @ColumnInfo(defaultValue = "0") val userEdited: Boolean = false,
    // 由 AI 从记录里提取、尚未被用户确认的行动项
    @ColumnInfo(defaultValue = "0") val isAiSuggested: Boolean = false,
)
