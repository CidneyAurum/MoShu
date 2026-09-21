package app.moshu.journal

import app.moshu.journal.ai.AiConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiConfigTest {

    @Test
    fun `空白字段被判为未配置`() {
        assertFalse(AiConfig(baseUrl = "   ", model = "deepseek-chat").valid)
        assertFalse(AiConfig(baseUrl = "https://api.deepseek.com", model = "  ").valid)
        assertFalse(AiConfig(baseUrl = "", model = "").valid)
    }

    @Test
    fun `裸域名与带路径的地址都算有效`() {
        assertTrue(AiConfig(baseUrl = "api.deepseek.com", model = "deepseek-chat").valid)
        assertTrue(AiConfig(baseUrl = "https://x.com/api/v4", model = "glm-4-flash").valid)
    }

    @Test
    fun `完整端点模式下的非 HTTP 地址被判无效`() {
        assertFalse(AiConfig(baseUrl = "ftp://x.com", model = "m", exactEndpoint = true).valid)
        assertTrue(AiConfig(baseUrl = "https://x.com/custom/chat", model = "m", exactEndpoint = true).valid)
    }

    @Test
    fun `normalized 去除字段首尾空白`() {
        val config = AiConfig(
            baseUrl = "  https://x.com/v1  ",
            apiKey = "  sk-abc  ",
            model = " m ",
            visionModel = " v ",
            authHeaderName = " Authorization ",
        ).normalized()
        assertEquals("https://x.com/v1", config.baseUrl)
        assertEquals("sk-abc", config.apiKey)
        assertEquals("m", config.model)
        assertEquals("v", config.visionModel)
        assertEquals("Authorization", config.authHeaderName)
    }

    @Test
    fun `视觉模型为空白时不启用图片理解`() {
        val config = AiConfig(
            baseUrl = "https://x.com/v1",
            model = "m",
            visionModel = "   ",
            allowImageAnalysis = true,
        )
        assertFalse(config.visionEnabled)
    }

    @Test
    fun `已知视觉模型的模板已预填`() {
        fun template(label: String) = AiConfig.TEMPLATES.first { it.label == label }
        assertEquals("gpt-4o-mini", template("OpenAI").visionModel)
        assertEquals("qwen-vl-plus", template("通义千问").visionModel)
        assertEquals("glm-4v-flash", template("智谱").visionModel)
        assertTrue(template("DeepSeek").visionModel.isEmpty())
        assertTrue(template("Kimi").visionModel.isEmpty())
    }

    @Test
    fun `基元律动模板保留可用模型 id`() {
        val template = AiConfig.TEMPLATES.first { it.label == "基元律动" }
        assertEquals("https://tokenrhythm.studio/v1", template.url)
        assertEquals("deepseek-v4-flash", template.model)
    }

    @Test
    fun `Ollama 模板指向局域网地址并附使用前提`() {
        val template = AiConfig.TEMPLATES.first { it.label.startsWith("Ollama") }
        assertTrue(template.url.startsWith("http://192.168."))
        assertFalse(template.url.contains("localhost"))
        assertTrue(template.hint.contains("OLLAMA_HOST"))
    }
}