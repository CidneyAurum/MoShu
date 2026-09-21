package app.moshu.journal.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [EntryEntity::class, TodoEntity::class, AttachmentEntity::class, AiReviewEntity::class, EntryFtsEntity::class],
    version = 5,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun entryDao(): EntryDao
    abstract fun todoDao(): TodoDao
    abstract fun attachmentDao(): AttachmentDao
    abstract fun aiReviewDao(): AiReviewDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `todos` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `text` TEXT NOT NULL, `sourceEntryId` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `dueEpochDay` INTEGER, `done` INTEGER NOT NULL DEFAULT 0)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_todos_sourceEntryId` ON `todos` (`sourceEntryId`)")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `entries` ADD COLUMN `uid` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `entries` ADD COLUMN `updatedAt` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `entries` ADD COLUMN `isPinned` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `entries` ADD COLUMN `aiState` TEXT NOT NULL DEFAULT 'idle'")
                db.execSQL("ALTER TABLE `entries` ADD COLUMN `aiError` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `entries` ADD COLUMN `manualMetadataMask` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE `entries` SET `uid` = lower(hex(randomblob(16))), `updatedAt` = `createdAt`, `aiState` = CASE WHEN `enriched` = 1 THEN 'succeeded' ELSE 'idle' END")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_entries_uid` ON `entries` (`uid`)")

                db.execSQL("ALTER TABLE `todos` ADD COLUMN `uid` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `todos` ADD COLUMN `updatedAt` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `todos` ADD COLUMN `completedAt` INTEGER")
                db.execSQL("ALTER TABLE `todos` ADD COLUMN `reminderAt` INTEGER")
                db.execSQL("ALTER TABLE `todos` ADD COLUMN `isUserCreated` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `todos` ADD COLUMN `userEdited` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE `todos` SET `uid` = lower(hex(randomblob(16))), `updatedAt` = `createdAt`, `completedAt` = CASE WHEN `done` = 1 THEN `createdAt` ELSE NULL END")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_todos_uid` ON `todos` (`uid`)")

                db.execSQL("CREATE TABLE IF NOT EXISTS `attachments` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `uid` TEXT NOT NULL DEFAULT '', `entryId` INTEGER NOT NULL, `localPath` TEXT NOT NULL, `mimeType` TEXT NOT NULL, `width` INTEGER NOT NULL, `height` INTEGER NOT NULL, `sortOrder` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, FOREIGN KEY(`entryId`) REFERENCES `entries`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_attachments_entryId` ON `attachments` (`entryId`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_attachments_uid` ON `attachments` (`uid`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `ai_reviews` (`periodKey` TEXT NOT NULL, `periodType` TEXT NOT NULL, `content` TEXT NOT NULL, `sourceUidsJson` TEXT NOT NULL, `generatedAt` INTEGER NOT NULL, PRIMARY KEY(`periodKey`))")

                db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS `entries_fts` USING FTS4(`content` TEXT NOT NULL, `summary` TEXT NOT NULL, `tagsJson` TEXT NOT NULL, content=`entries`)")
                db.execSQL("CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_entries_fts_BEFORE_UPDATE BEFORE UPDATE ON `entries` BEGIN DELETE FROM `entries_fts` WHERE `docid`=OLD.`rowid`; END")
                db.execSQL("CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_entries_fts_BEFORE_DELETE BEFORE DELETE ON `entries` BEGIN DELETE FROM `entries_fts` WHERE `docid`=OLD.`rowid`; END")
                db.execSQL("CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_entries_fts_AFTER_UPDATE AFTER UPDATE ON `entries` BEGIN INSERT INTO `entries_fts`(`docid`, `content`, `summary`, `tagsJson`) VALUES (NEW.`rowid`, NEW.`content`, NEW.`summary`, NEW.`tagsJson`); END")
                db.execSQL("CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_entries_fts_AFTER_INSERT AFTER INSERT ON `entries` BEGIN INSERT INTO `entries_fts`(`docid`, `content`, `summary`, `tagsJson`) VALUES (NEW.`rowid`, NEW.`content`, NEW.`summary`, NEW.`tagsJson`); END")
                db.execSQL("INSERT INTO `entries_fts`(`docid`, `content`, `summary`, `tagsJson`) SELECT `id`, `content`, `summary`, `tagsJson` FROM `entries`")
            }
        }

        /**
         * v4：记录 AI 产出的出处（模型 / 提示词版本），标记 AI 建议的行动，
         * 并让月度叙事能说明自己基于多少条记录生成。
         * 有了这些字段，改进提示词之后才可能识别并重新整理旧的条目。
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `entries` ADD COLUMN `aiModel` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `entries` ADD COLUMN `aiPromptVersion` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `todos` ADD COLUMN `isAiSuggested` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `ai_reviews` ADD COLUMN `model` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `ai_reviews` ADD COLUMN `promptVersion` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `ai_reviews` ADD COLUMN `entryCount` INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * v5：回收站与收藏。
         *
         * `deletedAt = 0` 表示正常条目，沿用旧行的默认值即可，无需回填。
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `entries` ADD COLUMN `deletedAt` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `entries` ADD COLUMN `isStarred` INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun build(context: Context): AppDatabase = Room.databaseBuilder(context, AppDatabase::class.java, "moshu.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
            .build()
    }
}
