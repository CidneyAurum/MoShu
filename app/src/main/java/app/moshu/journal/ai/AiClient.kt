package app.moshu.journal.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * 极简 OpenAI 兼容 chat/completions 客户端（BYOK）。
 * 使用 HttpURLConnection，无第三方网络依赖；密钥只在请求头中短暂出现，绝不写日志。
 */
object AiClient {

    data class ImageInput(val mimeType: String, val base64: String)

    sealed class Result {
        data class Ok(val text: String) : Result()
        data class Fail(val message: String, val retryable: Boolean = false) : Result()
    }

    suspend fun complete(
        config: AiConfig,
        system: String,
        user: String,
        temperature: Double = 0.3,
        images: List<ImageInput> = emptyList(),
    ): Result = withContext(Dispatchers.IO) {
        if (!config.valid) {
            return@withContext Result.Fail("尚未配置 AI 服务，请到「设置」选择服务商并填写密钥")
        }
        var connection: HttpURLConnection? = null
        try {
            val body = buildRequestBody(config, system, user, temperature, images)

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

            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val raw = BufferedReader(InputStreamReader(stream!!, Charsets.UTF_8)).use { it.readText() }

            if (code !in 200..299) {
                return@withContext Result.Fail(
                    message = "HTTP $code：${safeError(raw)}",
                    retryable = code == 408 || code == 429 || code >= 500,
                )
            }
            val contentNode = JSONObject(raw)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .get("content")
            val content = when (contentNode) {
                is String -> contentNode
                is JSONArray -> (0 until contentNode.length()).joinToString("") { index ->
                    contentNode.optJSONObject(index)?.optString("text").orEmpty()
                }
                else -> contentNode.toString()
            }
            Result.Ok(content.trim())
        } catch (e: Exception) {
            Result.Fail(e.message?.take(180) ?: "网络错误", retryable = true)
        } finally {
            connection?.disconnect()
        }
    }

    internal fun buildRequestBody(
        config: AiConfig,
        system: String,
        user: String,
        temperature: Double = 0.3,
        images: List<ImageInput> = emptyList(),
    ): String {
        val usableImages = images.take(9).takeIf { config.visionEnabled }.orEmpty()
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
            put("temperature", temperature)
            put("stream", false)
            put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", system))
                    .put(JSONObject().put("role", "user").put("content", userContent))
            )
        }.toString()
    }

    private fun safeError(raw: String): String = runCatching {
        val error = JSONObject(raw).optJSONObject("error")
        error?.optString("message").orEmpty().ifBlank { "服务暂时不可用" }.take(180)
    }.getOrDefault("服务暂时不可用")
}
