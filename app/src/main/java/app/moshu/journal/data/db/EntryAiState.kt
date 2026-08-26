package app.moshu.journal.data.db

enum class EntryAiState(val value: String) {
    IDLE("idle"),
    PENDING("pending"),
    RUNNING("running"),
    SUCCEEDED("succeeded"),
    FAILED("failed");

    companion object {
        fun from(value: String): EntryAiState = entries.firstOrNull { it.value == value } ?: IDLE
    }
}
