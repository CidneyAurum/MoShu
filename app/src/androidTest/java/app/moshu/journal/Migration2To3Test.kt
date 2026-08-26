package app.moshu.journal

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.moshu.journal.data.db.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration2To3Test {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

    @Test
    fun migrate2To3PreservesEntriesAndTodos() {
        helper.createDatabase(DB_NAME, 2).apply {
            execSQL("CREATE TABLE IF NOT EXISTS `entries` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `content` TEXT NOT NULL, `categoryId` INTEGER NOT NULL, `tagsJson` TEXT NOT NULL, `summary` TEXT NOT NULL, `mood` TEXT NOT NULL, `enriched` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL)")
            execSQL("CREATE TABLE IF NOT EXISTS `todos` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `text` TEXT NOT NULL, `sourceEntryId` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `dueEpochDay` INTEGER, `done` INTEGER NOT NULL DEFAULT 0)")
            execSQL("CREATE INDEX IF NOT EXISTS `index_todos_sourceEntryId` ON `todos` (`sourceEntryId`)")
            execSQL("INSERT INTO entries(content, categoryId, tagsJson, summary, mood, enriched, createdAt) VALUES ('旧记忆', 0, '[]', '', '', 0, 1000)")
            execSQL("INSERT INTO todos(text, sourceEntryId, createdAt, done) VALUES ('旧行动', 1, 1000, 0)")
            close()
        }

        helper.runMigrationsAndValidate(DB_NAME, 3, true, AppDatabase.MIGRATION_2_3).use { db ->
            db.query("SELECT content, uid, updatedAt FROM entries").use { cursor ->
                cursor.moveToFirst()
                assertEquals("旧记忆", cursor.getString(0))
                assertTrue(cursor.getString(1).isNotBlank())
                assertEquals(1000L, cursor.getLong(2))
            }
            db.query("SELECT text, uid FROM todos").use { cursor ->
                cursor.moveToFirst()
                assertEquals("旧行动", cursor.getString(0))
                assertTrue(cursor.getString(1).isNotBlank())
            }
        }
    }

    private fun assertTrue(value: Boolean) = org.junit.Assert.assertTrue(value)
    private companion object { const val DB_NAME = "migration-2-3" }
}
