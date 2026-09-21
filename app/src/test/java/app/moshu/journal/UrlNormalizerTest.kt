package app.moshu.journal

import app.moshu.journal.ai.UrlNormalizer
import org.junit.Assert.assertEquals
import org.junit.Test

class UrlNormalizerTest {

    @Test
    fun `裸域名自动补全`() {
        assertEquals(
            "https://api.deepseek.com/v1/chat/completions",
            UrlNormalizer.chatCompletionsUrl("api.deepseek.com")
        )
    }

    @Test
    fun `根地址补 v1 路径`() {
        assertEquals(
            "https://api.deepseek.com/v1/chat/completions",
            UrlNormalizer.chatCompletionsUrl("https://api.deepseek.com")
        )
    }

    @Test
    fun `v1 结尾只补 chat completions`() {
        assertEquals(
            "https://x.com/v1/chat/completions",
            UrlNormalizer.chatCompletionsUrl("https://x.com/v1/")
        )
    }

    @Test
    fun `基元律动基础地址可直接使用`() {
        assertEquals(
            "https://tokenrhythm.studio/v1/chat/completions",
            UrlNormalizer.chatCompletionsUrl("https://tokenrhythm.studio/v1")
        )
    }

    @Test
    fun `任意版本路径不重复插入 v1`() {
        assertEquals(
            "https://open.bigmodel.cn/api/paas/v4/chat/completions",
            UrlNormalizer.chatCompletionsUrl("https://open.bigmodel.cn/api/paas/v4")
        )
        assertEquals(
            "https://api.example.com/v3/chat/completions",
            UrlNormalizer.chatCompletionsUrl("https://api.example.com/v3")
        )
    }

    @Test
    fun `完整自定义端点不追加路径`() {
        assertEquals(
            "https://gateway.example.com/my-compatible-endpoint",
            UrlNormalizer.requestUrl("gateway.example.com/my-compatible-endpoint", exact = true)
        )
    }

    @Test
    fun `完整路径原样保留`() {
        val full = "https://x.com/v1/chat/completions"
        assertEquals(full, UrlNormalizer.chatCompletionsUrl(full))
    }

    @Test
    fun `自定义相对路径只补 chat completions`() {
        // 带路径时不再插入 /v1：之前会把 https://x.com/api/chat 拼成
        // https://x.com/api/chat/v1/chat/completions，直接 404。
        assertEquals(
            "https://my.host/openai/chat/completions",
            UrlNormalizer.chatCompletionsUrl("my.host/openai")
        )
    }

    @Test
    fun `query 参数保留`() {
        assertEquals(
            "https://gw.cn/v1/chat/completions?token=abc",
            UrlNormalizer.chatCompletionsUrl("https://gw.cn/v1?token=abc")
        )
    }
}
