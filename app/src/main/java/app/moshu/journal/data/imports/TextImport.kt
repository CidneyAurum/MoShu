package app.moshu.journal.data.imports

import android.content.Context
import android.net.Uri
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * 外部文本导入：把从其它应用分享进来的文字、以及导入的 .txt/.md 文件转成可入库的正文。
 *
 * 兼容性是这里的重点——用户从别处搬来的文本可能是 UTF-8、带 BOM、UTF-16（记事本
 * 「Unicode」另存）或者老中文 GBK，全都必须能正确读出来，否则导入就是一堆乱码。
 */
object TextImport {

    /** 单次导入的文本上限，避免误选一个几百 MB 的文件把内存打满。 */
    const val MAX_IMPORT_CHARS = 200_000

    private const val MAX_IMPORT_BYTES = 8 * 1024 * 1024

    /**
     * 按字节特征判定编码并解码。
     *
     * 顺序：BOM → 严格 UTF-8 → 无 BOM 的 UTF-16 → GBK。
     * 严格 UTF-8 放前面是因为它能成功解码时几乎不可能是别的编码；
     * 失败后才需要在「无 BOM UTF-16」与「GBK」之间判断。
     */
    fun decode(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        bomCharset(bytes)?.let { (charset, offset) ->
            return String(bytes, offset, bytes.size - offset, charset)
        }
        strictDecode(bytes, Charsets.UTF_8)?.let { return it }
        detectBomlessUtf16(bytes)?.let { return String(bytes, it) }
        return runCatching { String(bytes, charset("GBK")) }.getOrDefault(String(bytes, Charsets.UTF_8))
    }

    private fun bomCharset(bytes: ByteArray): Pair<Charset, Int>? = when {
        bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() ->
            Charsets.UTF_8 to 3
        bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> Charsets.UTF_16LE to 2
        bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> Charsets.UTF_16BE to 2
        else -> null
    }

    private fun strictDecode(bytes: ByteArray, charset: Charset): String? = runCatching {
        charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    }.getOrNull()

    /**
     * 无 BOM 的 UTF-16 判定。
     *
     * 只靠 NUL 字节间隔不成立：中文 UTF-16 的每个码位两个字节都非零
     * （汉字在 U+4E00–U+9FFF），只有 ASCII 才产生 NUL。所以补一条针对汉字的判据：
     * UTF-16LE 下第 i+1 字节是高位，中文文本里应密集落在 0x4E–0x9F。
     */
    private fun detectBomlessUtf16(bytes: ByteArray): Charset? {
        val limit = minOf(bytes.size, 4096)
        if (limit < 16) return null
        var evenZero = 0
        var oddZero = 0
        var leCjkHigh = 0
        var beCjkHigh = 0
        var pairs = 0
        var i = 0
        while (i + 1 < limit) {
            val low = bytes[i].toInt() and 0xff
            val high = bytes[i + 1].toInt() and 0xff
            if (low == 0) evenZero++
            if (high == 0) oddZero++
            if (high in 0x4E..0x9F) leCjkHigh++
            if (low in 0x4E..0x9F) beCjkHigh++
            pairs++
            i += 2
        }
        val minZeros = limit / 8
        return when {
            oddZero >= minZeros && oddZero > evenZero * 4 -> Charsets.UTF_16LE
            evenZero >= minZeros && evenZero > oddZero * 4 -> Charsets.UTF_16BE
            pairs >= 8 && leCjkHigh * 3 >= pairs * 2 -> Charsets.UTF_16LE
            pairs >= 8 && beCjkHigh * 3 >= pairs * 2 -> Charsets.UTF_16BE
            else -> null
        }
    }

    /** 读取一个 content:// 文本文件；失败返回 null 而不是抛出。 */
    fun readText(context: Context, uri: Uri): String? = runCatching {
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(64 * 1024)
            val output = java.io.ByteArrayOutputStream()
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > MAX_IMPORT_BYTES) break
                output.write(buffer, 0, read)
            }
            output.toByteArray()
        } ?: return null
        normalize(decode(bytes))
    }.getOrNull()

    /**
     * 清洗成可直接入库的正文：统一换行、去掉 BOM 残留与 NUL，
     * 并裁剪到上限。压缩包/二进制被误判成文本时，NUL 清理能避免脏数据入库。
     */
    fun normalize(raw: String): String = raw
        .removePrefix("\uFEFF")
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .replace("\u0000", "")
        .trim()
        .take(MAX_IMPORT_CHARS)

    /** 文件是否像压缩包（用于给出「请先解压」这类提示，而不是导入一堆乱码）。 */
    fun looksLikeArchive(context: Context, uri: Uri): Boolean = runCatching {
        val name = context.contentResolver
            .query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }
            .orEmpty()
        name.lowercase().let { it.endsWith(".zip") || it.endsWith(".epub") || it.endsWith(".docx") }
    }.getOrDefault(false)
}
