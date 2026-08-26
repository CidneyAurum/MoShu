package app.moshu.journal.data.db

object Category {
    const val LIFE = 0
    const val WORK = 1
    const val IDEA = 2
    val NAMES = listOf("生活", "工作", "灵感")

    fun nameOf(id: Int): String = NAMES.getOrElse(id) { "生活" }
}
