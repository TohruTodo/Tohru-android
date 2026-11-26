package io.github.tohrutodo.tohru.task

data class Task(
    val id: String,
    val title: String,
    val done: Boolean = false,
    val archived: Boolean = false,
    val created: Long = System.currentTimeMillis(),
    val updated: Long = System.currentTimeMillis(),
    val body: String = "",
) {
    // Parsing is pure for a title, and the tree reads this repeatedly.
    val parsed: ParsedTitle = TaskParser.parse(title)
}
