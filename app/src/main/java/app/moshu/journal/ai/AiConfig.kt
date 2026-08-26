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
    val valid: Boolean
        get() = baseUrl.isNotBlank() && model.isNotBlank()

    val visionEnabled: Boolean
        get() = valid && allowImageAnalysis && visionModel.isNotBlank()

    /** OpenAI 兼容接口模板 */
    data class Template(val label: String, val url: String, val model: String)

    companion object {
        val TEMPLATES = listOf(
            Template("DeepSeek", "https://api.deepseek.com", "deepseek-chat"),
            Template("OpenAI", "https://api.openai.com/v1", "gpt-4o-mini"),
            Template("基元律动", "https://tokenrhythm.studio/v1", "deepseek-v4-flash"),
            Template("通义千问", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus"),
            Template("智谱", "https://open.bigmodel.cn/api/paas/v4", "glm-4-flash"),
            Template("Kimi", "https://api.moonshot.cn/v1", "moonshot-v1-8k"),
            Template("Ollama 本地", "http://localhost:11434/v1", "qwen2.5"),
        )
    }
}
