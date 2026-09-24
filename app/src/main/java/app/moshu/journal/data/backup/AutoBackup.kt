package app.moshu.journal.data.backup

import android.content.Context
import android.net.Uri
import app.moshu.journal.data.db.AppDatabase
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 本机自动备份。
 *
 * 手动导出解决的是「换手机 / 存到别处」，但用户不会记得每周点一次。
 * 自动备份只写进应用私有目录：不需要任何存储权限，也不会把日记内容甩到公共相册附近，
 * 代价是卸载应用时会一起消失——这点必须在设置页写清楚。
 */
object AutoBackup {

    /** 私有目录名。手动导出的备份和这里互不干扰。 */
    const val DIR = "auto_backups"

    /** 保留最近几份。三份足够覆盖「改坏了想退回两步」，又不会把存储吃光。 */
    const val KEEP = 3

    fun dir(context: Context): File = File(context.filesDir, DIR)

    /** 现有自动备份，最新的在前。 */
    fun list(context: Context): List<File> = listIn(dir(context))

    /**
     * 目录里的备份文件，最新的在前。
     *
     * 按修改时间排序而不是文件名：文件名来自系统时间，用户手动改过时间或跨时区后
     * 字典序就不再等于时间序，那时按名字裁剪会删掉真正最新的那份。
     */
    fun listIn(dir: File): List<File> =
        dir.listFiles { file -> file.isFile && file.name.endsWith(EXTENSION) }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()

    fun fileFor(context: Context, at: Long): File =
        File(dir(context), "墨枢自动备份-${stamp(at)}$EXTENSION")

    /** 立刻备份一份并清理旧档，返回写出的文件。 */
    suspend fun runOnce(context: Context, db: AppDatabase, at: Long = System.currentTimeMillis()): File {
        val target = fileFor(context, at)
        target.parentFile?.mkdirs()
        // 先写临时文件再改名：备份中途被取消（进程被杀、页面退出）时，
        // 半截的 zip 不会以「最新备份」的身份留在列表里，恢复时才发现文件是坏的。
        val temp = File(target.parentFile, target.name + TEMP_SUFFIX)
        temp.delete()
        try {
            BackupManager.exportBackup(context, db, Uri.fromFile(temp))
            // renameTo 在同一目录内是原子替换；失败时至少要把临时文件清掉。
            check(temp.renameTo(target)) { "无法写入备份文件" }
        } finally {
            temp.delete()
        }
        prune(context)
        return target
    }

    /** 清掉上次中断留下的临时文件。启动时调用一次即可。 */
    fun clearTemps(context: Context) {
        dir(context).listFiles { file -> file.name.endsWith(TEMP_SUFFIX) }?.forEach { it.delete() }
    }

    /** 只保留最新的 [keep] 份。 */
    fun prune(context: Context, keep: Int = KEEP) {
        pruneDir(dir(context), keep)
    }

    /** 删掉多出来的旧档，返回被删掉的文件名。保留的是最新的 [keep] 份。 */
    fun pruneDir(dir: File, keep: Int): List<String> =
        listIn(dir).drop(keep).map { file ->
            runCatching { file.delete() }
            file.name
        }

    /** 恢复某个自动备份。文件在私有目录里，用 file:// Uri 交给同一套恢复流程。 */
    suspend fun restore(
        context: Context,
        db: AppDatabase,
        file: File,
        replace: Boolean,
        onProgress: (String) -> Unit = {},
    ): BackupManager.RestoreResult =
        BackupManager.restore(context, db, Uri.fromFile(file), replace, onProgress)

    private const val EXTENSION = ".moshu"

    /** 临时后缀。不能以 [EXTENSION] 结尾，否则会被当成一份正常备份列出来。 */
    private const val TEMP_SUFFIX = ".tmp"

    private fun stamp(at: Long): String = SimpleDateFormat("yyyyMMdd-HHmm", Locale.CHINA).format(Date(at))
}