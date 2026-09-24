package app.moshu.journal

import app.moshu.journal.data.backup.AutoBackup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 自动备份的保留策略。
 *
 * 这段逻辑出错不会报错，只会表现为「昨天的备份莫名不见了」，
 * 所以必须把「保留的是最新的几份」钉成断言。
 */
class AutoBackupTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun backup(name: String, modifiedAt: Long): File =
        temp.newFile(name).apply { setLastModified(modifiedAt) }

    @Test
    fun `只列出备份文件 忽略临时文件和别的文件`() {
        backup("墨枢自动备份-20260901-0900.moshu", 1_000)
        backup("墨枢自动备份-20260902-0900.moshu.tmp", 2_000)
        backup("随手放的说明.txt", 3_000)
        val listed = AutoBackup.listIn(temp.root).map { it.name }
        assertEquals(listOf("墨枢自动备份-20260901-0900.moshu"), listed)
    }

    @Test
    fun `列表按时间倒序 最新的在前`() {
        backup("a.moshu", 1_000)
        backup("b.moshu", 3_000)
        backup("c.moshu", 2_000)
        assertEquals(listOf("b.moshu", "c.moshu", "a.moshu"), AutoBackup.listIn(temp.root).map { it.name })
    }

    @Test
    fun `裁剪保留最新的三份 删掉更早的`() {
        backup("old1.moshu", 1_000)
        backup("old2.moshu", 2_000)
        backup("new1.moshu", 3_000)
        backup("new2.moshu", 4_000)
        backup("new3.moshu", 5_000)
        val removed = AutoBackup.pruneDir(temp.root, keep = 3)
        assertEquals(listOf("old2.moshu", "old1.moshu"), removed)
        assertEquals(
            listOf("new3.moshu", "new2.moshu", "new1.moshu"),
            AutoBackup.listIn(temp.root).map { it.name },
        )
    }

    @Test
    fun `不足保留数量时一份都不删`() {
        backup("only.moshu", 1_000)
        assertTrue(AutoBackup.pruneDir(temp.root, keep = 3).isEmpty())
        assertEquals(1, AutoBackup.listIn(temp.root).size)
    }

    @Test
    fun `目录不存在时不抛异常`() {
        val missing = File(temp.root, "nope")
        assertTrue(AutoBackup.listIn(missing).isEmpty())
        assertTrue(AutoBackup.pruneDir(missing, keep = 3).isEmpty())
    }

    @Test
    fun `保留数量为零时清空目录`() {
        backup("a.moshu", 1_000)
        backup("b.moshu", 2_000)
        assertEquals(2, AutoBackup.pruneDir(temp.root, keep = 0).size)
        assertTrue(AutoBackup.listIn(temp.root).isEmpty())
    }
}