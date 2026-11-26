package io.github.tohrutodo.tohru.task

// Prefix lists that change how titles are grouped. Stored as comma-separated
// values in settings; parser keys stay lowercase.
object TaskSettings {
    const val DEFAULT_INBOX = TaskParser.INBOX
    const val DEFAULT_NOTE = "note"

    fun parseList(raw: String): Set<String> = raw.split(',')
        .mapNotNull { it.trim().lowercase().takeIf(String::isNotEmpty) }
        .toSet()

    fun encode(values: Set<String>): String = values.sorted().joinToString(", ")
}
