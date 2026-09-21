package app.moshu.journal.ui.components

/**
 * 记录模板。
 *
 * 空白面对输入框时最难的往往是「从哪句开始」。模板只提供结构，不替用户写内容；
 * 全部是纯文本，直接填进草稿，用户可以随意改，也不涉及任何网络请求。
 */
data class EntryTemplate(
    val key: String,
    val label: String,
    val hint: String,
    val body: String,
)

val ENTRY_TEMPLATES: List<EntryTemplate> = listOf(
    EntryTemplate(
        key = "blank",
        label = "空白",
        hint = "想写什么就写什么",
        body = "",
    ),
    EntryTemplate(
        key = "morning",
        label = "晨间",
        hint = "给今天定个调子",
        body = "睡醒时的感觉：\n\n今天最重要的一件事：\n\n有点担心的：\n",
    ),
    EntryTemplate(
        key = "review",
        label = "复盘",
        hint = "把一天过一遍",
        body = "今天做得好的：\n\n卡住的地方：\n\n明天要调整的：\n",
    ),
    EntryTemplate(
        key = "reading",
        label = "读书",
        hint = "记下读到的东西",
        body = "书名 / 章节：\n\n印象最深的一句：\n\n我的想法：\n\n可以用在哪：\n",
    ),
    EntryTemplate(
        key = "idea",
        label = "灵感",
        hint = "趁还没忘先写下来",
        body = "想法：\n\n为什么觉得可行：\n\n下一步先做什么：\n",
    ),
)

/** 模板默认值。取不到时用空白模板，避免界面因一个 key 失配而崩。 */
fun templateOf(key: String): EntryTemplate = ENTRY_TEMPLATES.firstOrNull { it.key == key } ?: ENTRY_TEMPLATES.first()
