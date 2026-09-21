package app.moshu.journal

import app.moshu.journal.ai.AiClient
import app.moshu.journal.ai.AiConfig
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

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

    @Test
    fun `默认请求体不带 response_format 与 max_tokens`() {
        val json = JSONObject(AiClient.buildRequestBody(config, "system", "hello"))
        assertFalse(json.has("response_format"))
        assertFalse(json.has("max_tokens"))
        assertEquals(0.3, json.getDouble("temperature"), 0.0001)
    }

    @Test
    fun `jsonMode 添加 response_format`() {
        val json = JSONObject(AiClient.buildRequestBody(config, "system", "hello", jsonMode = true))
        assertEquals("json_object", json.getJSONObject("response_format").getString("type"))
    }

    @Test
    fun `maxTokens 添加 max_tokens`() {
        val json = JSONObject(AiClient.buildRequestBody(config, "system", "hello", maxTokens = 512))
        assertEquals(512, json.getInt("max_tokens"))
    }

    @Test
    fun `temperature 为 null 时不发送该字段`() {
        val json = JSONObject(AiClient.buildRequestBody(config, "system", "hello", temperature = null))
        assertFalse(json.has("temperature"))
    }

    @Test
    fun `鉴权失败与地址错误的错误码映射为中文指引`() {
        assertTrue(AiClient.httpErrorMessage(401, "{}", "text-model").contains("密钥无效或已过期"))
        assertTrue(AiClient.httpErrorMessage(403, "{}", "text-model").contains("密钥无效或已过期"))
        val notFound = AiClient.httpErrorMessage(404, "{}", "text-model")
        assertTrue(notFound.contains("接口地址或模型名不正确"))
        assertTrue(notFound.contains("text-model"))
        assertTrue(AiClient.httpErrorMessage(400, "{}", "text-model").contains("请求被拒绝"))
        assertTrue(AiClient.httpErrorMessage(429, "{}", "text-model").contains("请求过于频繁"))
        assertTrue(AiClient.httpErrorMessage(503, "{}", "text-model").contains("服务商暂时不可用"))
    }

    @Test
    fun `服务端错误报文里的密钥被脱敏`() {
        val raw = """{"error":{"message":"Incorrect API key provided: sk-abc123XYZ. Check your key"}}"""
        val message = AiClient.httpErrorMessage(401, raw, "m")
        assertFalse(message.contains("sk-abc123XYZ"))
        assertTrue(message.contains("sk-***"))
        assertFalse(message.contains("super-secret-key"))
    }

    @Test
    fun `AIza 前缀密钥同样被脱敏`() {
        val raw = """{"error":{"message":"API key AIzaSyD-1234567890abcdef is invalid"}}"""
        val message = AiClient.httpErrorMessage(400, raw, "m")
        assertFalse(message.contains("AIzaSyD-1234567890abcdef"))
        assertTrue(message.contains("sk-***"))
    }

    @Test
    fun `网络异常按类型映射为中文提示且不含英文原文`() {
        val host = AiClient.networkErrorMessage(UnknownHostException("api.example.com"))
        assertTrue(host.contains("无法连接"))
        assertFalse(host.contains("api.example.com"))

        assertTrue(AiClient.networkErrorMessage(SocketTimeoutException("timed out")).contains("超时"))
        assertTrue(AiClient.networkErrorMessage(SSLException("CertPathValidatorException")).contains("HTTPS"))

        val generic = AiClient.networkErrorMessage(IllegalStateException("boom"))
        assertTrue(generic.contains("网络"))
        assertFalse(generic.contains("boom"))
    }

    @Test
    fun `图片数量超限返回可读原因`() {
        val overLimit = List(7) { AiClient.ImageInput("image/jpeg", "AAAA") }
        val error = AiClient.imageLimitError(overLimit)
        assertNotNull(error)
        assertTrue(error!!.contains("6"))

        assertNull(AiClient.imageLimitError(List(6) { AiClient.ImageInput("image/jpeg", "AAAA") }))
    }

    @Test
    fun `图片总体积超限被拒绝`() {
        val oversized = "A".repeat(6 * 1024 * 1024 + 1)
        assertNotNull(AiClient.imageLimitError(listOf(AiClient.ImageInput("image/jpeg", oversized))))
    }
}