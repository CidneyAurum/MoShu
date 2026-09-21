package app.moshu.journal.ai

/**
 * 从用户填写的服务地址里解析主机名。
 *
 * 此前详情页、设置页与设置 ViewModel 各写了一份实现，三处对「解析不出」的处理
 * 还不一致（有的回退原文、有的回退空串）。统一到一处后，调用方只决定展示兜底文案。
 */
object Hosts {

    /** 解析主机名；地址为空或解析不出时返回空串，由调用方决定兜底文案。 */
    fun of(url: String): String {
        val value = url.trim()
        if (value.isBlank()) return ""
        return runCatching { java.net.URI(if (value.contains("://")) value else "https://$value").host }
            .getOrNull()?.takeIf { it.isNotBlank() }
            ?: value.trimEnd('/').lowercase()
    }
}