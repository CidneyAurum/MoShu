package app.moshu.journal

import app.moshu.journal.data.imports.TextImport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 外部文本导入的编码判定。
 *
 * 用户从别的应用搬来的文本可能是 UTF-8、带 BOM、UTF-16（记事本「Unicode」另存）
 * 或老中文 GBK，任何一种读错都是满屏乱码。这里把各种形态都钉住。
 */
class TextImportTest {

    private val body = "今天下午和产品组过了新版本的排期。\n有点累，但方向清楚了。"

    private fun roundTrip(bytes: ByteArray) = TextImport.normalize(TextImport.decode(bytes))

    private fun bytesOf(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private val utf8Bom = bytesOf(0xEF, 0xBB, 0xBF)
    private val utf16LeBom = bytesOf(0xFF, 0xFE)
    private val utf16BeBom = bytesOf(0xFE, 0xFF)

    @Test
    fun `UTF-8 与带 BOM 的 UTF-8`() {
        assertEquals(body, roundTrip(body.toByteArray(Charsets.UTF_8)))
        assertEquals(body, roundTrip(utf8Bom + body.toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun `带 BOM 的 UTF-16 两种字节序`() {
        assertEquals(body, roundTrip(utf16LeBom + body.toByteArray(Charsets.UTF_16LE)))
        assertEquals(body, roundTrip(utf16BeBom + body.toByteArray(Charsets.UTF_16BE)))
    }

    @Test
    fun `无 BOM 的 UTF-16 也能识别`() {
        // 关键用例：中文 UTF-16 每个码位两个字节都非零（汉字在 U+4E00–U+9FFF），
        // 只有 ASCII 才产生 NUL，所以「靠 NUL 间隔判断 UTF-16」对纯中文文本必然失效。
        assertEquals(body, roundTrip(body.toByteArray(Charsets.UTF_16LE)))
        assertEquals(body, roundTrip(body.toByteArray(Charsets.UTF_16BE)))
    }

    @Test
    fun `GBK 老中文文本`() {
        assertEquals(body, roundTrip(body.toByteArray(charset("GBK"))))
    }

    @Test
    fun `纯 ASCII 不会被误判`() {
        val ascii = "Chapter 1\n\nplain english text."
        assertEquals(ascii, roundTrip(ascii.toByteArray(Charsets.US_ASCII)))
    }

    @Test
    fun `任何编码都不应残留 NUL`() {
        for (bytes in listOf(
            body.toByteArray(Charsets.UTF_16LE),
            body.toByteArray(Charsets.UTF_16BE),
            utf16LeBom + body.toByteArray(Charsets.UTF_16LE),
            body.toByteArray(charset("GBK")),
        )) {
            assertFalse("出现 NUL 说明编码判错了", roundTrip(bytes).contains('\u0000'))
        }
    }

    @Test
    fun `清洗换行与 BOM 残留`() {
        assertEquals("a\nb", TextImport.normalize("a\r\nb"))
        assertEquals("a\nb", TextImport.normalize("a\rb"))
        assertEquals("abc", TextImport.normalize("\uFEFFabc"))
        assertEquals("ab", TextImport.normalize("a\u0000b"))
        assertEquals("x", TextImport.normalize("  x  "))
    }

    @Test
    fun `超长文本被裁剪到上限`() {
        val huge = "字".repeat(TextImport.MAX_IMPORT_CHARS + 5000)
        assertEquals(TextImport.MAX_IMPORT_CHARS, TextImport.normalize(huge).length)
    }

    @Test
    fun `空输入返回空串而不是抛异常`() {
        assertEquals("", TextImport.decode(ByteArray(0)))
        assertEquals("", TextImport.normalize(""))
    }

    @Test
    fun `二进制垃圾不会产出可用正文`() {
        // 随机字节可能被某种编码解出字符，但至少不应保留 NUL
        val junk = ByteArray(256) { (it * 37 % 256).toByte() }
        val decoded = TextImport.normalize(TextImport.decode(junk))
        assertFalse(decoded.contains('\u0000'))
        assertTrue("解码结果应当很短或为空", decoded.length <= TextImport.MAX_IMPORT_CHARS)
    }
}
