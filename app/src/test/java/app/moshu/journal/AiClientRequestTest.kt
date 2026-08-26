package app.moshu.journal

import app.moshu.journal.ai.AiClient
import app.moshu.journal.ai.AiConfig
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiClientRequestTest {
    private val config = AiConfig(
        baseUrl = "https://example.com/v1",
        apiKey = "super-secret-key",
        model = "text-model",
        visionModel = "vision-model",
        allowImageAnalysis = true,
    )

    @Test
    fun `请求正文从不包含密钥`() {
        val body = AiClient.buildRequestBody(config, "system", "hello")
        assertFalse(body.contains("super-secret-key"))
    }

    @Test
    fun `开启图片理解时使用视觉模型与多模态正文`() {
        val body = AiClient.buildRequestBody(
            config,
            "system",
            "看看这张图",
            images = listOf(AiClient.ImageInput("image/jpeg", "YWJj")),
        )
        val json = JSONObject(body)
        assertEquals("vision-model", json.getString("model"))
        val content = json.getJSONArray("messages").getJSONObject(1).getJSONArray("content")
        assertEquals("text", content.getJSONObject(0).getString("type"))
        assertTrue(content.getJSONObject(1).getJSONObject("image_url").getString("url").startsWith("data:image/jpeg;base64,"))
    }

    @Test
    fun `关闭图片理解时自动退回文本模型`() {
        val body = AiClient.buildRequestBody(
            config.copy(allowImageAnalysis = false),
            "system",
            "hello",
            images = listOf(AiClient.ImageInput("image/jpeg", "YWJj")),
        )
        val json = JSONObject(body)
        assertEquals("text-model", json.getString("model"))
        assertEquals("hello", json.getJSONArray("messages").getJSONObject(1).getString("content"))
    }
}
