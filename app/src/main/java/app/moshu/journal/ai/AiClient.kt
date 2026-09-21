package app.moshu.journal.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * 极简 OpenAI 兼容 chat/completions 客户端（BYOK）。
 * 使用 HttpURLConnection，无第三方网络依赖；密钥只在请求头中短暂出现，绝不写日志。
 */
object AiClient {

    data class ImageInput(val mimeType: String, val base64: String)

    sealed class Result {
        data class Ok(
            val text: String,
            /** 提示词 token 数，用于让 BYOK 用户知道这次调用大概花了多少。 */
            val promptTokens: Int = 0,
            val completionTokens: Int = 0,
            val finishReason: String = "",
            /** 服务端实际使用的模型名；部分网关会改写请求里的模型。 */
            val model: String = "",
        ) : Result()

        data class Fail(val message: String, val retryable: Boolean = false) : Result()
    }

    /** 模型列表查询结果，单独成类避免与补全 [Result] 混淆。 */
    sealed class ModelsResult {
        data class Ok(val models: List<String>) : ModelsResult()
        data class Fail(val message: String, val retryable: Boolean = false) : ModelsResult()
    }

    /** 单次请求的图片上限：数量与 base64 总字节。超出直接拒绝，不做静默截断。 */
    internal const val MAX_IMAGE_COUNT = 6
    internal const val MAX_IMAGE_BASE64_BYTES = 6L * 1024 * 1024

    private const val GENERIC_ERROR = "服务暂时不可用"

    // 部分模型/网关不支持 response_format 或 temperature，遇到 400 后按进程记住，后续请求不再发送。
    @Volatile private var jsonModeUnsupported = false
    @Volatile private var temperatureUnsupported = false

    suspend fun complete(
        config: AiConfig,
        system: String,
        user: String,
        temperature: Double = 0.3,
        images: List<ImageInput> = emptyList(),
        maxTokens: Int? = null,
        jsonMode: Boolean = false,
        /**
         * 之前的对话轮次（role to content），插在 system 与当前提问之间。
         * 问墨枢的追问（「那后来呢？」）必须带上历史，否则会被当成孤立的新问题。
         */
        history: List<Pair<String, String>> = emptyList(),
    ): Result = withContext(Dispatchers.IO) {
        val cfg = config.normalized()
        if (!cfg.valid) {
            return@withContext Result.Fail("尚未配置 AI 服务，请到「设置」选择服务商并填写密钥")
        }
        val usableImages = if (cfg.visionEnabled) images else emptyList()
        // 图片超出上限时明确报错，而不是悄悄只发一部分：用户以为整条记录都被读过了。
        imageLimitError(usableImages)?.let { return@withContext Result.Fail(it) }

        var useJsonMode = jsonMode && !jsonModeUnsupported
        var useTemperature = !temperatureUnsupported
        var retries = 0
        var result: Result? = null
        while (result == null) {
            when (val attempt = performRequest(
                config = cfg,
                system = system,
                user = user,
                temperature = if (useTemperature) temperature else null,
                images = usableImages,
                maxTokens = maxTokens,
                jsonMode = useJsonMode,
                history = history,
            )) {
                is Attempt.Done -> result = attempt.result
                is Attempt.HttpFailure -> {
                    val code = attempt.code
                    val mentionsJson = code == 400 && useJsonMode &&
                        attempt.raw.contains("response_format", ignoreCase = true)
                    val mentionsTemperature = code == 400 && useTemperature &&
                        attempt.raw.contains("temperature", ignoreCase = true)
                    if (retries < 2 && (mentionsJson || mentionsTemperature)) {
                        // 网关拒绝某个可选参数时把它摘掉重试一次，并把结论记到进程内，
                        // 免得每次整理都撞同一个 400。
                        if (mentionsJson) {
                            jsonModeUnsupported = true
                            useJsonMode = false
                        } else {
                            temperatureUnsupported = true
                            useTemperature = false
                        }
                        retries++
                    } else {
                        result = Result.Fail(
                            message = httpErrorMessage(code, attempt.raw, cfg.model),
                            retryable = code == 408 || code == 429 || code >= 500,
                        )
                    }
                }
            }
        }
        result ?: Result.Fail("网络请求失败，请稍后重试")
    }

    /**
     * GET {base}/models，用于设置页做模型发现。
     * 复用与补全相同的地址归一化、鉴权头、错误映射与脱敏。
     */
    suspend fun listModels(config: AiConfig): ModelsResult = withContext(Dispatchers.IO) {
        val cfg = config.normalized()
        if (cfg.baseUrl.isEmpty()) {
            return@withContext ModelsResult.Fail("尚未填写服务地址")
        }
        var connection: HttpURLConnection? = null
        try {
            val url = UrlNormalizer.modelsUrl(cfg.baseUrl)
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 30_000
                setRequestProperty("Content-Type", "application/json")
                if (cfg.authHeaderName.isNotBlank() && cfg.apiKey.isNotBlank()) {
                    setRequestProperty(cfg.authHeaderName, cfg.authPrefix + cfg.apiKey)
                }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            if (stream == null) {
                return@withContext ModelsResult.Fail("服务返回了空响应（HTTP $code）", retryable = true)
            }
            val raw = BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
            if (code !in 200..299) {
                return@withContext ModelsResult.Fail(
                    message = httpErrorMessage(code, raw, cfg.model),
                    retryable = code == 408 || code == 429 || code >= 500,
                )
            }
            val data = runCatching { JSONObject(raw).optJSONArray("data") }.getOrNull()
            val models = buildList {
                if (data != null) {
                    for (i in 0 until data.length()) {
                        val id = data.optJSONObject(i)?.optString("id").orEmpty().trim()
                        if (id.isNotEmpty()) add(id)
                    }
                }
            }
            ModelsResult.Ok(models)
        } catch (e: Exception) {
            ModelsResult.Fail(networkErrorMessage(e), retryable = true)
        } finally {
            connection?.disconnect()
        }
    }

    /** 单次请求，不做重试决策；把 HTTP 失败原样交回调用方判断能否降级重试。 */
    private fun performRequest(
        config: AiConfig,
        system: String,
        user: String,
        temperature: Double?,
        images: List<ImageInput>,
        maxTokens: Int?,
        jsonMode: Boolean,
        history: List<Pair<String, String>> = emptyList(),
    ): Attempt {
        var connection: HttpURLConnection? = null
        return try {
            val body = buildRequestBody(config, system, user, temperature, images, maxTokens, jsonMode, history)

            val url = UrlNormalizer.requestUrl(config.baseUrl, config.exactEndpoint)
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 60_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                if (config.authHeaderName.isNotBlank() && config.apiKey.isNotBlank()) {
                    setRequestProperty(config.authHeaderName, config.authPrefix + config.apiKey)
                }
            }

            // 不用 setFixedLengthStreamingMode：流式模式下 HttpURLConnection 无法在
            // 401 之后透明重发请求，会改抛 HttpRetryException，用户就看不到可排查的状态码了。
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            // 204/304 之类可能既没有 body 也没有 errorStream，此时 stream 为 null。
            // 直接 !! 会抛 NPE，用户看到的是「网络错误」这种毫无线索的提示。
            if (stream == null) {
                return Attempt.Done(Result.Fail("服务返回了空响应（HTTP $code）", retryable = true))
            }
            val raw = BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }

            if (code !in 200..299) Attempt.HttpFailure(code, raw) else Attempt.Done(parseResponse(raw))
        } catch (e: Exception) {
            Attempt.Done(Result.Fail(networkErrorMessage(e), retryable = true))
        } finally {
            connection?.disconnect()
        }
    }

    private fun parseResponse(raw: String): Result {
        val json = try {
            JSONObject(raw)
        } catch (_: Exception) {
            return Result.Fail("返回格式无法识别")
        }
        // 空 choices 数组会抛 IndexOutOfBoundsException；content 为 JSON null 时
        // 旧写法会得到字符串 "null"。两种情况都必须先判掉，否则会暴露英文异常。
        val choices = json.optJSONArray("choices")
        if (choices == null || choices.length() == 0) {
            return Result.Fail("服务未返回结果（可能被内容策略拦截）")
        }
        val choice = choices.optJSONObject(0)
            ?: return Result.Fail("服务未返回结果（可能被内容策略拦截）")
        val finishReason = choice.optString("finish_reason").orEmpty()
        if (finishReason == "length") {
            return Result.Fail("回答被长度限制截断，请重试", retryable = true)
        }
        val message = choice.optJSONObject("message")
            ?: return Result.Fail("服务未返回结果（可能被内容策略拦截）")
        val content = contentText(message.opt("content"))
        // 推理模型（DeepSeek-R1、QwQ 等）把正文放在 reasoning_content，content 为空。
        val text = content.ifBlank { contentText(message.opt("reasoning_content")) }
        if (text.isBlank()) {
            return Result.Fail("模型返回了空内容，可能是该模型不支持当前接口格式", retryable = true)
        }
        val usage = json.optJSONObject("usage")
        return Result.Ok(
            text = text.trim(),
            promptTokens = usage?.optInt("prompt_tokens", 0) ?: 0,
            completionTokens = usage?.optInt("completion_tokens", 0) ?: 0,
            finishReason = finishReason,
            model = json.optString("model").orEmpty(),
        )
    }

    /** 兼容 string / 多模态数组 / JSON null 三种 content 形态。 */
    private fun contentText(node: Any?): String = when (node) {
        null, JSONObject.NULL -> ""
        is String -> node
        is JSONArray -> (0 until node.length()).joinToString("") { index ->
            node.optJSONObject(index)?.optString("text").orEmpty()
        }
        else -> node.toString().takeIf { it != "null" }.orEmpty()
    }

    internal fun buildRequestBody(
        config: AiConfig,
        system: String,
        user: String,
        temperature: Double? = 0.3,
        images: List<ImageInput> = emptyList(),
        maxTokens: Int? = null,
        jsonMode: Boolean = false,
        history: List<Pair<String, String>> = emptyList(),
    ): String {
        val usableImages = images.take(MAX_IMAGE_COUNT).takeIf { config.visionEnabled }.orEmpty()
        val userContent: Any = if (usableImages.isEmpty()) {
            user
        } else {
            JSONArray().apply {
                put(JSONObject().put("type", "text").put("text", user))
                usableImages.forEach { image ->
                    put(
                        JSONObject()
                            .put("type", "image_url")
                            .put("image_url", JSONObject().put("url", "data:${image.mimeType};base64,${image.base64}"))
                    )
                }
            }
        }
        return JSONObject().apply {
            put("model", if (usableImages.isEmpty()) config.model else config.visionModel)
            // 部分模型的思考模式不接受 temperature，降级重试时会传 null。
            if (temperature != null) put("temperature", temperature)
            put("stream", false)
            if (maxTokens != null) put("max_tokens", maxTokens)
            // 只有服务商明确支持 JSON 模式时才发，否则会被 400 拒绝。
            if (jsonMode) put("response_format", JSONObject().put("type", "json_object"))
            put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", system))
                    .also { array ->
                        // 历史轮次按时间顺序插在 system 之后、当前提问之前。
                        history.takeLast(MAX_HISTORY_TURNS).forEach { (role, content) ->
                            array.put(JSONObject().put("role", role).put("content", content))
                        }
                    }
                    .put(JSONObject().put("role", "user").put("content", userContent))
            )
        }.toString()
    }

    /** 单次请求最多携带的历史轮数，避免上下文无限增长。 */
    internal const val MAX_HISTORY_TURNS = 12

    /** 返回超限原因；未超限返回 null。抽成纯函数便于离线测试。 */
    internal fun imageLimitError(images: List<ImageInput>): String? {
        if (images.size > MAX_IMAGE_COUNT) {
            return "一条记录最多分析 $MAX_IMAGE_COUNT 张图片，请减少后再试"
        }
        val totalBytes = images.sumOf { it.base64.length.toLong() }
        if (totalBytes > MAX_IMAGE_BASE64_BYTES) {
            return "图片总大小超出上限（约 ${MAX_IMAGE_BASE64_BYTES / (1024 * 1024)} MB），请减少图片或换更小的图"
        }
        return null
    }

    /**
     * 把 HTTP 状态码翻译成非技术用户能照做的中文指引；
     * 服务端原始文本只在确实补充信息时附在后面，且一定先脱敏。
     */
    internal fun httpErrorMessage(code: Int, raw: String, model: String): String {
        val guidance = when (code) {
            401, 403 -> "密钥无效或已过期，请到设置页重新填写 API Key"
            404 -> "接口地址或模型名不正确（当前模型：$model）"
            400 -> "请求被拒绝，可能是模型名错误，或该模型不支持当前参数"
            429 -> "请求过于频繁，稍后会自动重试"
            in 500..599 -> "服务商暂时不可用（HTTP $code）"
            else -> "请求失败（HTTP $code）"
        }
        val suffix = if (code in 500..599) "" else "（HTTP $code）"
        val detail = redact(safeError(raw)).take(180)
        val informative = detail.isNotBlank() && detail != GENERIC_ERROR && !guidance.contains(detail)
        return if (informative) "$guidance$suffix：$detail" else "$guidance$suffix"
    }

    /** 按异常类型给出中文提示，绝不把平台英文异常直接暴露给用户。 */
    internal fun networkErrorMessage(error: Throwable): String = when (error) {
        is UnknownHostException -> "无法连接，请检查网络或服务地址"
        is SocketTimeoutException -> "服务响应超时，请稍后重试"
        is SSLException -> "安全连接失败，请确认地址是 HTTPS"
        else -> "网络请求失败，请检查网络后重试"
    }

    /** 服务商回显的报文里常带密钥前缀，展示前必须抹掉。 */
    internal fun redact(text: String): String {
        var out = text
        KEY_PATTERNS.forEach { pattern -> out = out.replace(pattern, "sk-***") }
        return out
    }

    private val KEY_PATTERNS = listOf(
        Regex("sk-[A-Za-z0-9_\\-]{4,}"),
        Regex("AIza[0-9A-Za-z_\\-]{4,}"),
    )

    private fun safeError(raw: String): String = runCatching {
        val error = JSONObject(raw).optJSONObject("error")
        error?.optString("message").orEmpty().ifBlank { GENERIC_ERROR }.take(180)
    }.getOrDefault(GENERIC_ERROR)

    private sealed interface Attempt {
        data class Done(val result: Result) : Attempt
        data class HttpFailure(val code: Int, val raw: String) : Attempt
    }
}