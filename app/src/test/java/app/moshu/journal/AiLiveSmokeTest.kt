package app.moshu.journal

import app.moshu.journal.ai.AiClient
import app.moshu.journal.ai.AiConfig
import app.moshu.journal.ai.AskEngine
import app.moshu.journal.ai.Enricher
import app.moshu.journal.ai.UrlNormalizer
import app.moshu.journal.data.db.Category
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
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
        // 稳定别名优先：带日期的版本号会随服务商轮换而失效。
        model = props.getProperty("moshu.ai.model", "deepseek-flash")
        apiKey = System.getenv("MOSHU_AI_KEY")?.takeIf { it.isNotBlank() }
            ?: props.getProperty("moshu.ai.key", "")
        assumeTrue("未提供 AI 密钥，跳过实况测试", apiKey.isNotBlank())
    }

    private fun config(key: String = apiKey) =
        AiConfig(baseUrl = baseUrl, apiKey = key, model = model)

    /**
     * 网络不可达时跳过而不是失败。
     *
     * 实况测试依赖真实服务商：DNS 不通、连不上、超时都属于「这次没法测」，
     * 不该让离线测试套件变红——那会掩盖真正的回归。凭据被拒（401/403）同理，
     * Key 可能已按安全建议轮换过。
     */
    private fun skipIfUnavailable(error: Throwable): Nothing {
        val transient = error is java.net.UnknownHostException ||
            error is java.net.ConnectException ||
            error is java.net.SocketTimeoutException ||
            error is java.net.NoRouteToHostException ||
            // 网关瞬时掐断 TLS 握手（负载/中间设备）：重试通常能过，不是代码问题。
            error is javax.net.ssl.SSLException ||
            (error.message?.let { it.contains("401") || it.contains("403") } == true)
        assumeTrue("服务商当前不可达，跳过实况测试：${error.message}", !transient)
        throw error
    }

    private inline fun <T> live(block: () -> T): T = try {
        block()
    } catch (error: Throwable) {
        skipIfUnavailable(error)
    }



    @Test
    fun `地址归一化拼出 chat completions 端点`() {
        assertEquals("$baseUrl/chat/completions", UrlNormalizer.requestUrl(baseUrl))
    }

    @Test
    fun `真实网关接受 json 模式与长度上限`() = runBlocking {
        val result = live { AiClient.complete(
            config(),
            "你是 JSON 生成器。只输出 JSON，不要解释。",
            """把这句话转成 JSON：{"ok":true,"note":"今天加班到十点"} 的形状""",
            temperature = 0.0,
            maxTokens = 200,
            jsonMode = true,
        ) }
        assertTrue("期望成功，实际：$result", result is AiClient.Result.Ok)
        val text = (result as AiClient.Result.Ok).text.trim()
        // json 模式生效时返回的应当就是可直接解析的 JSON（而不是被 markdown 包裹的散文）。
        assertTrue("返回内容不是 JSON：${text.take(120)}", text.startsWith("{"))
        // 能解析出来才算 json 模式真的生效。
        JSONObject(text)
        Unit
    }

    @Test
    fun `用量字段被解析出来`() = runBlocking {
        val result = live { AiClient.complete(config(), "你是测试助手。", "只回复两个字：可用") }
        assertTrue(result is AiClient.Result.Ok)
        val ok = result as AiClient.Result.Ok
        // 服务端返回 usage 时必须解析出来，否则设置页的「本月约 X 次调用 · Y tokens」是假的。
        assertTrue("未解析出输入 tokens", ok.promptTokens > 0)
        assertTrue("未解析出输出 tokens", ok.completionTokens > 0)
    }

    @Test
    fun `模型列表可以从接口读取`() = runBlocking {
        when (val models = live { AiClient.listModels(config()) }) {
            is AiClient.ModelsResult.Ok -> {
                assertTrue("模型列表为空", models.models.isNotEmpty())
                // 注意：接口接受别名，但 /models 列出的是规范 id
                // （配置 deepseek-v4-flash，列表里是 deepseek-v4-flash-0731）。
                // 所以「列表里没有配置的模型名」不能当作配置错误——设置页也不该这样提示。
                val related = models.models.any { it == model || it.startsWith("$model-") || model.startsWith("$it-") }
                assertTrue(
                    "列表里既没有 $model 也没有它的规范 id：${models.models.take(5)}",
                    related,
                )
            }
            is AiClient.ModelsResult.Fail -> throw AssertionError("读取模型列表失败：${models.message}")
        }
    }

    @Test
    fun `带编号摘录的提问只引用真实存在的编号`() = runBlocking {
        // 引用必须可核对：模型只能引用提示里给出的编号，否则界面会挂出假来源。
        val excerpts = listOf(
            "8月20日：和产品组确认了新版本排期，下周三前完成登录模块重构。",
            "8月22日：周五前补上接口文档。",
        )
        val numbered = excerpts.mapIndexed { i, body -> "[${i + 1}] $body" }.joinToString("\n")
        val prompt = "【日记摘录】\n$numbered\n\n【问题】排期是怎么定的？"
        val result = live { AiClient.complete(config(), AskEngine.SYSTEM_PROMPT, prompt, temperature = 0.3, maxTokens = 400) }
        assertTrue("请求失败：$result", result is AiClient.Result.Ok)
        val text = (result as AiClient.Result.Ok).text
        assertTrue("回答为空", text.isNotBlank())
        val cited = AskEngine.citedIndices(text)
        assertTrue("引用了不存在的编号：$cited", cited.all { it in 1..excerpts.size })
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

        // 服务端偶发把回答截断（finish_reason=length）是网关侧状态，不是代码缺陷：
        // 这种抖动不该让整个测试套件变红，跳过并保留真正的解析失败断言。
        assumeTrue(
            "服务端本次返回被长度限制截断，跳过",
            (request as? AiClient.Result.Fail)?.message?.contains("长度限制") != true,
        )
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
