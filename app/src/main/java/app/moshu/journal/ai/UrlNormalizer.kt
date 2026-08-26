package app.moshu.journal.ai

/**
 * Base URL 归一化（对齐页枢的四种输入形式）：
 * 1. 裸域名        api.deepseek.com            → https://api.deepseek.com/v1/chat/completions
 * 2. 完整根地址    https://api.deepseek.com    → 同上
 * 3. 带 /v1       https://x.com/v1            → https://x.com/v1/chat/completions
 * 4. 其他版本路径  https://x.com/api/v4        → https://x.com/api/v4/chat/completions
 * 5. 自定义相对路径 https://x.com/openai        → https://x.com/openai/v1/chat/completions
 * 另：已含 /chat/completions 的直接沿用；query 参数原样保留。
 */
object UrlNormalizer {

    private val versionSegment = Regex("v[0-9]+(?:[a-z0-9._-]*)?", RegexOption.IGNORE_CASE)

    /**
     * [exact] 为 true 时，地址被视为完整 OpenAI 兼容请求端点，不再猜测或拼接路径。
     */
    fun requestUrl(raw: String, exact: Boolean = false): String =
        if (exact) exactUrl(raw) else chatCompletionsUrl(raw)

    fun chatCompletionsUrl(raw: String): String {
        var s = raw.trim()
        require(s.isNotEmpty()) { "Base URL 不能为空" }
        if (!s.contains("://")) s = "https://$s"

        val queryIndex = s.indexOf('?')
        var base = s
        var query = ""
        if (queryIndex >= 0) {
            query = s.substring(queryIndex)
            base = s.substring(0, queryIndex)
        }
        base = base.trimEnd('/')

        val lastSegment = base.substringAfterLast('/')
        return when {
            base.endsWith("/chat/completions", ignoreCase = true) -> base + query
            versionSegment.matches(lastSegment) -> "$base/chat/completions$query"
            "/deployments/" in base.lowercase() -> "$base/chat/completions$query"
            else -> "$base/v1/chat/completions$query"
        }
    }

    fun exactUrl(raw: String): String {
        var value = raw.trim()
        require(value.isNotEmpty()) { "请求端点不能为空" }
        if (!value.contains("://")) value = "https://$value"
        require(value.startsWith("https://", true) || value.startsWith("http://", true)) {
            "仅支持 HTTP 或 HTTPS 地址"
        }
        return value
    }
}
