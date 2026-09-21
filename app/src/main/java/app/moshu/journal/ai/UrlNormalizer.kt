package app.moshu.journal.ai

/**
 * Base URL 归一化（对齐页枢的四种输入形式）：
 * 1. 裸域名        api.deepseek.com            → https://api.deepseek.com/v1/chat/completions
 * 2. 完整根地址    https://api.deepseek.com    → 同上
 * 3. 带 /v1       https://x.com/v1            → https://x.com/v1/chat/completions
 * 4. 其他版本路径  https://x.com/api/v4        → https://x.com/api/v4/chat/completions
 * 5. 自定义相对路径 https://x.com/openai        → https://x.com/openai/chat/completions
 * 另：已含 /chat/completions 的直接沿用；query 参数原样保留。
 */
object UrlNormalizer {

    /**
     * 仅匹配完整的版本段：v1 / v4 / v1beta / v1.5。
     * 旧写法会把 v2ray、v1beta 这类自定义路径误判成版本号，导致路径拼接错误。
     */
    private val versionSegment = Regex("v[0-9]+(?:beta|\\.[0-9]+)?", RegexOption.IGNORE_CASE)

    /**
     * [exact] 为 true 时，地址被视为完整 OpenAI 兼容请求端点，不再猜测或拼接路径。
     */
    fun requestUrl(raw: String, exact: Boolean = false): String =
        if (exact) exactUrl(raw) else chatCompletionsUrl(raw)

    fun chatCompletionsUrl(raw: String): String {
        val (base, query) = splitEndpoint(raw, "Base URL 不能为空")
        if (base.endsWith("/chat/completions", ignoreCase = true)) return base + query

        val lastSegment = base.substringAfterLast('/')
        return when {
            versionSegment.matches(lastSegment) -> "$base/chat/completions$query"
            "/deployments/" in base.lowercase() -> "$base/chat/completions$query"
            // 带自定义路径的网关（如 https://x.com/api/chat）其 API 根就是该路径，
            // 只补 /chat/completions；只有完全没写路径时才需要补 /v1。
            hasPath(base) -> "$base/chat/completions$query"
            else -> "$base/v1/chat/completions$query"
        }
    }

    /** 推导 GET {base}/models 的地址，用于设置页的模型发现。 */
    fun modelsUrl(raw: String): String {
        val (endpoint, _) = splitEndpoint(raw, "Base URL 不能为空")
        var base = endpoint
        if (base.endsWith("/chat/completions", ignoreCase = true)) {
            base = base.dropLast("/chat/completions".length).trimEnd('/')
        }
        if (base.endsWith("/models", ignoreCase = true)) return base

        val lastSegment = base.substringAfterLast('/')
        return when {
            versionSegment.matches(lastSegment) -> "$base/models"
            "/deployments/" in base.lowercase() -> "$base/models"
            hasPath(base) -> "$base/models"
            else -> "$base/v1/models"
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

    /** 去掉 query、补全 scheme、去掉结尾斜杠，并拆出 query。 */
    private fun splitEndpoint(raw: String, emptyMessage: String): Pair<String, String> {
        var s = raw.trim()
        require(s.isNotEmpty()) { emptyMessage }
        if (!s.contains("://")) s = "https://$s"

        val queryIndex = s.indexOf('?')
        val base = (if (queryIndex >= 0) s.substring(0, queryIndex) else s).trimEnd('/')
        val query = if (queryIndex >= 0) s.substring(queryIndex) else ""
        return base to query
    }

    /** 判断 scheme://host 之后是否还有路径（host 后出现了 '/'）。 */
    private fun hasPath(url: String): Boolean {
        val schemeEnd = url.indexOf("://")
        val rest = if (schemeEnd >= 0) url.substring(schemeEnd + 3) else url
        return rest.contains('/')
    }
}