package io.github.tohrutodo.tohru.task

// Parses titles shaped like "group:", "group(topic):" or plain text. The
// group segment before ':' is stored as prefix, the parenthesized one as topic.
//
// Group names use '_' for a literal space; topics keep spaces and underscores
// as written. Unicode letters and combining marks are valid so titles in
// Vietnamese and other scripts stay usable.
data class ParsedTitle(val prefix: String?, val topic: String?, val body: String)

object TaskParser {
    const val INBOX = "inbox"

    private const val LETTER = """[\p{L}\p{M}\p{N}]"""
    private const val PREFIX = """$LETTER+(?:[_-]+$LETTER+)*"""
    private const val TOPIC = """$LETTER+(?:[ _-]+$LETTER+)*"""
    private val pattern = Regex("""^($PREFIX)(?:\(($TOPIC)\))?:\s*(.+)$""")
    private val timePattern = Regex("""^\d{1,2}:\d{2}(?:\s|$)""")
    private val schemePrefixes = setOf(
        "http", "https", "ftp", "file", "mailto", "tel", "data", "javascript",
    )

    @Volatile
    private var inboxAliases: Set<String> = setOf(INBOX)

    // The inbox alias is always reserved: a real prefix with the same key
    // would collide with the untagged bucket.
    fun configureInbox(aliases: Set<String>) {
        inboxAliases = aliases.mapNotNull { alias ->
            alias.trim().lowercase().takeIf { it.isNotEmpty() }
        }.toSet() + INBOX
    }

    fun parse(raw: String): ParsedTitle {
        val trimmed = raw.trim()
        if (timePattern.containsMatchIn(trimmed)) return ParsedTitle(null, null, trimmed)
        val match = pattern.matchEntire(trimmed) ?: return ParsedTitle(null, null, trimmed)
        val prefix = match.groupValues[1]
            .lowercase()
            .replace('_', ' ')
            .replace(Regex(" +"), " ")
            .trim()
        val topic = match.groupValues[2]
            .trim()
            .lowercase()
            .ifEmpty { null }
        val body = match.groupValues[3].trim()
        if (body.isEmpty()) return ParsedTitle(null, null, trimmed)
        // Reject URL/path-like shapes that happen to look like tags.
        if (prefix in schemePrefixes || body.startsWith("//") || body.startsWith("\\")) {
            return ParsedTitle(null, null, trimmed)
        }
        if (prefix in inboxAliases) return ParsedTitle(null, null, body)
        return ParsedTitle(prefix = prefix, topic = topic, body = body)
    }

    fun groupKey(parsed: ParsedTitle): String = parsed.prefix ?: INBOX
}
