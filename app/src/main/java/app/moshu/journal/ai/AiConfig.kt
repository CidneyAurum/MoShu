package app.moshu.journal.ai

data class AiConfig(
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
    val visionModel: String = "",
    val allowImageAnalysis: Boolean = false,
    // 非标网关可自定义鉴权头；authHeaderName 为空则完全不发送鉴权头
    val authHeaderName: String = "Authorization",
    val authPrefix: String = "Bearer ",
    /** 开启后将 baseUrl 作为完整请求地址，不自动补全版本或 chat/completions。 */
    val exactEndpoint: Boolean = false,
) {
    /** 去掉所有字段的首尾空白。authPrefix 是有意保留的（"Bearer " 尾部空格有意义）。 */
    fun normalized(): AiConfig = copy(
        baseUrl = baseUrl.trim(),
        apiKey = apiKey.trim(),
        model = model.trim(),
        visionModel = visionModel.trim(),
        authHeaderName = authHeaderName.trim(),
    )

    /**
     * 空白字段与无法解析的地址都算未配置。
     * 只做非空判断会让 "   " 或非法地址显示成「已配置」，之后每次调用都失败。
     */
    val valid: Boolean
        get() {
            val trimmed = normalized()
            if (trimmed.baseUrl.isEmpty() || trimmed.model.isEmpty()) return false
            return runCatching { UrlNormalizer.requestUrl(trimmed.baseUrl, trimmed.exactEndpoint) }.isSuccess
        }

    val visionEnabled: Boolean
        get() = valid && allowImageAnalysis && visionModel.isNotBlank()

    /** OpenAI 兼容接口模板。[hint] 给出模板可用的前提条件，[visionModel] 为已知可用的视觉模型。 */
    data class Template(
        val label: String,
        val url: String,
        val model: String,
        val visionModel: String = "",
        val hint: String = "",
    )

    companion object {
        val TEMPLATES = listOf(
            Template("DeepSeek", "https://api.deepseek.com", "deepseek-chat"),
            Template("OpenAI", "https://api.openai.com/v1", "gpt-4o-mini", visionModel = "gpt-4o-mini"),
            // 稳定别名优先：带日期的版本号会随服务商轮换而失效
            Template("基元律动", "https://tokenrhythm.studio/v1", "deepseek-flash"),
            Template(
                "通义千问",
                "https://dashscope.aliyuncs.com/compatible-mode/v1",
                "qwen-plus",
                visionModel = "qwen-vl-plus",
            ),
            Template(
                "智谱",
                "https://open.bigmodel.cn/api/paas/v4",
                "glm-4-flash",
                visionModel = "glm-4v-flash",
            ),
            Template(
                "Google Gemini",
                "https://generativelanguage.googleapis.com/v1beta/openai",
                "gemini-2.0-flash",
                visionModel = "gemini-2.0-flash",
            ),
            Template("Kimi", "https://api.moonshot.cn/v1", "moonshot-v1-8k"),
            Template(
                label = "Ollama（需填电脑局域网 IP）",
                // Android 上的 localhost 是手机自己，永远连不到电脑上的 Ollama；
                // 这里给一个局域网占位地址，用户按提示改成电脑 IP 即可。
                url = "http://192.168.1.10:11434/v1",
                model = "qwen2.5",
                hint = "需在电脑上设置 OLLAMA_HOST=0.0.0.0，并填写电脑的局域网 IP",
            ),
        )
    }
}