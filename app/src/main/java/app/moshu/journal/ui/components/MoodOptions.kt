package app.moshu.journal.ui.components

/**
 * 情绪选项的唯一来源。
 *
 * 原先这套「值 + 文案」在记录页筛选块、详情页编辑器、卡片表情和回顾页文案里各写一份，
 * 改一处就会漏一处（历史上就出现过筛选块只覆盖三种情绪、neutral/bad 永远筛不出来的问题）。
 */
val MOOD_OPTIONS: List<Pair<String, String>> = listOf(
    "great" to "✨ 很好",
    "good" to "🙂 不错",
    "neutral" to "😌 平静",
    "low" to "😕 低落",
    "bad" to "😞 难过",
)

private val MOOD_LABELS: Map<String, String> = MOOD_OPTIONS.toMap()

/** 情绪的中文标签；未知值原样返回，避免把模型返回的怪值显示成空白。 */
fun moodLabel(value: String): String = MOOD_LABELS[value] ?: value

/** 卡片上只放表情，节省一行空间。 */
fun moodEmoji(mood: String): String = when (mood) {
    "great" -> "✨"
    "good" -> "🙂"
    "neutral" -> "😌"
    "low" -> "😕"
    "bad" -> "😞"
    else -> ""
}