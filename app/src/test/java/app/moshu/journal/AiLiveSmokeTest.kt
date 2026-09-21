package app.moshu.journal

import app.moshu.journal.ai.AiClient
import app.moshu.journal.ai.AiConfig
import app.moshu.journal.ai.Enricher
import app.moshu.journal.ai.UrlNormalizer
import app.moshu.journal.data.db.Category
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Properties

/**
 * 实况冒烟测试：用真实服务商接口跑通「请求 → 响应 → 解析 → 落库模型」的完整链路。
 *
 * 默认跳过，不会影响离线的 CI。启用方式（任选其一）：
 *  - 环境变量 `MOSHU_AI_KEY`
 *  - 未跟踪的 `local.properties`：`moshu.ai.key=...`
 * 服务地址与模型同样从 local.properties 的 `moshu.ai.baseUrl` / `moshu.ai.model` 读取。
 *
 * 密钥只从本机未跟踪文件读取，绝不写入源码或测试资源。
 */
class AiLiveSmokeTest {

    private lateinit var baseUrl: String
    private lateinit var model: String
    private var apiKey: String = ""

    @Before
    fun loadLocalConfig() {
        val props = Properties()
        // 单元测试的工作目录是模块目录（app/），local.properties 在项目根，两处都找。
        listOf(File("../local.properties"), File("local.properties"))
            .firstOrNull { it.isFile }
            ?.inputStream()?.use { props.load(it) }
        baseUrl = props.getProperty("moshu.ai.baseUrl", "https://tokenrhythm.studio/v1")
        model = props.getProperty("moshu.ai.model", "deepseek-v4-flash")
        apiKey = System.getenv("MOSHU_AI_KEY")?.takeIf { it.isNotBlank() }
            ?: props.getProperty("moshu.ai.key", "")
        assumeTrue("未提供 AI 密钥，跳过实况测试", apiKey.isNotBlank())
    }

    private fun config(key: String = apiKey) =
        AiConfig(baseUrl = baseUrl, apiKey = key, model = model)

    @Test
    fun `地址归一化拼出 chat completions 端点`() {
        assertEquals("$baseUrl/chat/completions", UrlNormalizer.requestUrl(baseUrl))
    }

    @Test
    fun `真实接口返回可用的补全结果`() = runBlocking {
        val result = AiClient.complete(config(), "你是测试助手。", "只回复两个字：可用")
        assertTrue("期望成功，实际：$result", result is AiClient.Result.Ok)
        assertTrue((result as AiClient.Result.Ok).text.isNotBlank())
    }

    @Test
    fun `真实接口能整理出一条中文随手记`() = runBlocking {
        val note = "今天下午和产品组过了新版本的排期，决定下周三前完成登录模块的重构。" +
            "有点累，但方向清楚了。记得周五前把接口文档补上。"
        val (request, enrichment) = Enricher.enrich(config(), note)

        assertTrue("请求失败：$request", request is AiClient.Result.Ok)
        assertNotNull("模型返回的内容无法解析为整理结果：${(request as AiClient.Result.Ok).text}", enrichment)

        val result = enrichment!!
        assertTrue("分类越界：${result.categoryId}", result.categoryId in Category.LIFE..Category.IDEA)
        assertTrue("概括为空", result.summary.isNotBlank())
        assertTrue("情绪不在允许集合内：${result.mood}", result.mood in MOODS)
        assertTrue("没有提取出任何标签", result.tags.isNotEmpty())
        // 文中「周五前把接口文档补上」是明确承诺，应当被提取成行动项。
        assertTrue("没有提取出行动项", result.todos.isNotEmpty())
    }

    @Test
    fun `密钥无效时返回不可重试的失败而不是抛异常`() = runBlocking {
        val result = AiClient.complete(config(key = "sk_tr_definitely_invalid"), "sys", "hi")
        assertTrue("期望失败，实际：$result", result is AiClient.Result.Fail)
        val fail = result as AiClient.Result.Fail
        assertFalse("鉴权失败不应被标记为可重试（实际信息：${fail.message}）", fail.retryable)
        assertTrue("失败信息应包含状态码，实际：${fail.message}", fail.message.contains("HTTP"))
    }

    @Test
    fun `地址或模型为空时明确提示尚未配置`() = runBlocking {
        val result = AiClient.complete(AiConfig(baseUrl = "", model = ""), "sys", "hi")
        assertTrue("期望失败，实际：$result", result is AiClient.Result.Fail)
        assertTrue((result as AiClient.Result.Fail).message.contains("尚未配置"))
    }

    @Test
    fun `填了地址与模型但没有密钥时按无鉴权请求发送`() = runBlocking {
        // 本地 Ollama 一类网关本来就不需要密钥，所以这里不拦截，
        // 而是真的发出去；对云端服务商应当收到 401。
        val result = AiClient.complete(AiConfig(baseUrl = baseUrl, model = model), "sys", "hi")
        assertTrue("期望失败，实际：$result", result is AiClient.Result.Fail)
        val fail = result as AiClient.Result.Fail
        assertTrue("应带上服务端状态码，实际：${fail.message}", fail.message.contains("HTTP"))
        assertFalse("鉴权失败不应被标记为可重试（实际信息：${fail.message}）", fail.retryable)
    }

    private companion object {
        val MOODS = setOf("great", "good", "neutral", "low", "bad")
    }
}
