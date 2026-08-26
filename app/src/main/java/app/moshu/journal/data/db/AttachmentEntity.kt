package app.moshu.journal.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(
    tableName = "attachments",
    foreignKeys = [
        ForeignKey(
            entity = EntryEntity::class,
            parentColumns = ["id"],
            childColumns = ["entryId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("entryId"), Index(value = ["uid"], unique = true)],
)
data class AttachmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(defaultValue = "''") val uid: String = UUID.randomUUID().toString(),
    val entryId: Long,
    val localPath: String,
    val mimeType: String = "image/jpeg",
    val width: Int,
    val height: Int,
    val sortOrder: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
)
