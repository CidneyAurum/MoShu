package app.moshu.journal.ui.components

import app.moshu.journal.data.ReEnrichResult
import app.moshu.journal.data.db.EntryAiState

/**
 * AI 状态的唯一定义处。
 *
 * 以前卡片标签、详情页整理卡片、设置页测试按钮各自写一套文案，
 * 「整理中」「AI 正在整理」「正在整理」三种说法指的是同一件事，
 * 用户在不同页面看到的状态对不上。所有面向用户的 AI 文案都从这里取。
 */
object AiActions {

    /** 失败重试的唯一按钮文案。三处入口共用，避免出现「重试」「重新整理」两种叫法。 */
    const val RETRY_LABEL = "重新整理"

    /** 紧凑标签：卡片一行里用，必须短。 */
    fun shortLabel(state: String): String = when (EntryAiState.from(state)) {
        EntryAiState.IDLE -> "未整理"
        EntryAiState.PENDING -> "等待整理"
        EntryAiState.RUNNING -> "整理中"
        EntryAiState.SUCCEEDED -> "已整理"
        EntryAiState.FAILED -> "整理失败"
    }

    /** 详情页标题：有空间把状态说完整。 */
    fun title(state: String): String = when (EntryAiState.from(state)) {
        EntryAiState.IDLE -> "尚未启用 AI 整理"
        EntryAiState.PENDING -> "等待 AI 整理"
        EntryAiState.RUNNING -> "AI 正在整理"
        EntryAiState.SUCCEEDED -> "已整理"
        EntryAiState.FAILED -> "这次没有整理成功"
    }

    /** 详情页说明：状态为不可用/失败时给一句下一步提示。 */
    fun hint(state: String, error: String): String = when (EntryAiState.from(state)) {
        EntryAiState.FAILED -> error.ifBlank { "可以再试一次；如果反复失败，检查设置页的服务地址与 Key。" }
        EntryAiState.IDLE -> "连接 AI 后，墨枢会补全概括、标签、情绪和行动项。"
        EntryAiState.PENDING, EntryAiState.RUNNING -> "整理在后台进行，完成后会在「今天」页提示。"
        EntryAiState.SUCCEEDED -> ""
    }

    /** 是否值得在详情页展示「重新整理」入口。 */
    fun canRetry(state: String): Boolean = EntryAiState.from(state) != EntryAiState.RUNNING

    /**
     * 一次重新整理的可见回执。
     *
     * 返回 null 表示不需要提示——目前只有「已提交整理」这一种，
     * 因为详情页的状态卡片本身就会变成「等待 AI 整理」。
     */
    fun reEnrichMessage(result: ReEnrichResult): String? = when (result) {
        ReEnrichResult.Enriched -> null
        ReEnrichResult.NotConfigured -> "尚未配置 AI 服务，请到设置页填写。"
        ReEnrichResult.EmptyContent -> "正文为空，没有可整理的内容。"
        ReEnrichResult.AllManual -> "概括与标签已由你手动设置，本次只重新提取行动项。"
    }

    /** 恢复 AI 管理后的回执，语义与重试不同，所以单独一套。 */
    fun reclaimMessage(result: ReEnrichResult): String = when (result) {
        ReEnrichResult.Enriched, ReEnrichResult.AllManual -> "已恢复 AI 管理，正在重新整理。"
        ReEnrichResult.NotConfigured -> "已恢复 AI 管理；配置 AI 服务后会重新整理。"
        ReEnrichResult.EmptyContent -> "已恢复 AI 管理，但正文为空，无法整理。"
    }
}