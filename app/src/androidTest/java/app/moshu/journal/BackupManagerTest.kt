package app.moshu.journal

import android.net.Uri
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.moshu.journal.data.backup.BackupManager
import app.moshu.journal.data.db.AppDatabase
import app.moshu.journal.data.db.EntryEntity
import app.moshu.journal.data.db.TodoEntity
import app.moshu.journal.data.settings.SettingsRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipFile

@RunWith(AndroidJUnit4::class)
class BackupManagerTest {

    @Test
    fun exportExcludesApiKeyAndMergeRestoreDeduplicatesUuid() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val restored = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val backup = File(context.cacheDir, "backup-test.moshu")
        val fakeKey = "test-api-key-must-never-be-exported"

        try {
            SettingsRepository(context).saveAi(
                baseUrl = "https://example.com/v1",
                model = "test-model",
                apiKey = fakeKey,
            )
            val entryId = source.entryDao().insert(
                EntryEntity(uid = "entry-stable-uuid", content = "一条需要保留的记忆", createdAt = 1_000L)
            )
            source.todoDao().insert(
                TodoEntity(uid = "todo-stable-uuid", text = "一个行动", sourceEntryId = entryId, createdAt = 2_000L)
            )

            BackupManager.exportBackup(context, source, Uri.fromFile(backup))

            val archiveText = ZipFile(backup).use { zip ->
                zip.entries().asSequence()
                    .filterNot { it.isDirectory }
                    .joinToString("\n") { item ->
                        zip.getInputStream(item).use { it.readBytes().toString(Charsets.UTF_8) }
                    }
            }
            assertFalse(archiveText.contains(fakeKey))
            assertFalse(archiveText.contains("ai_key_encrypted"))

            val first = BackupManager.restore(context, restored, Uri.fromFile(backup), replace = false)
            val second = BackupManager.restore(context, restored, Uri.fromFile(backup), replace = false)

            assertEquals(1, first.entries)
            assertEquals(1, first.todos)
            assertEquals(0, second.entries)
            assertEquals(0, second.todos)
            assertEquals(1, restored.entryDao().allOnce().size)
            assertEquals(1, restored.todoDao().allOnce().size)
        } finally {
            source.close()
            restored.close()
            backup.delete()
        }
    }
}
