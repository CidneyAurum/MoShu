package app.moshu.journal

import app.moshu.journal.ai.UrlNormalizer
import org.junit.Assert.assertEquals
import org.junit.Test

/** R24：版本段识别与自定义路径拼接。 */
class UrlNormalizerVersionTest {

    @Test
    fun `v2ray 不是版本段`() {
        assertEquals(
            "https://x.com/v2ray/chat/completions",
            UrlNormalizer.chatCompletionsUrl("https://x.com/v2ray"),
        )
    }

    @Test
    fun `v1beta 是版本段`() {
        assertEquals(
            "https://x.com/v1beta/chat/completions",
            UrlNormalizer.chatCompletionsUrl("https://x.com/v1beta"),
        )
    }

    @Test
    fun `v1 与 v4 是版本段`() {
        assertEquals("https://x.com/v1/chat/completions", UrlNormalizer.chatCompletionsUrl("https://x.com/v1"))
        assertEquals("https://x.com/v4/chat/completions", UrlNormalizer.chatCompletionsUrl("https://x.com/v4"))
    }

    @Test
    fun `v1 点 5 这类小版本号也是版本段`() {
        assertEquals(
            "https://x.com/v1.5/chat/completions",
            UrlNormalizer.chatCompletionsUrl("https://x.com/v1.5"),
        )
    }

    @Test
    fun `多段自定义路径不再插入 v1`() {
        assertEquals(
            "https://x.com/api/chat/chat/completions",
            UrlNormalizer.chatCompletionsUrl("https://x.com/api/chat"),
        )
    }

    @Test
    fun `完全无路径时才补 v1`() {
        assertEquals("https://x.com/v1/chat/completions", UrlNormalizer.chatCompletionsUrl("https://x.com"))
        assertEquals(
            "https://api.deepseek.com/v1/chat/completions",
            UrlNormalizer.chatCompletionsUrl("api.deepseek.com"),
        )
    }

    @Test
    fun `models 地址按同样规则推导`() {
        assertEquals("https://x.com/v1/models", UrlNormalizer.modelsUrl("https://x.com/v1"))
        assertEquals("https://api.deepseek.com/v1/models", UrlNormalizer.modelsUrl("api.deepseek.com"))
        assertEquals("https://x.com/v1/models", UrlNormalizer.modelsUrl("https://x.com/v1/chat/completions"))
        assertEquals("https://x.com/v1/models", UrlNormalizer.modelsUrl("https://x.com/v1?token=abc"))
    }
}