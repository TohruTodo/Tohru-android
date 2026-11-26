package io.github.tohrutodo.tohru.task

// Persisted manual ordering. Lists are ordered node ids:
//   groups   -> prefix keys
//   children -> prefix -> ["k:<taskId>", "t:<topic>", ...]
//   tasks    -> "<prefix>|<topic>" -> ["<taskId>", ...]
// Manual order wins over the automatic activity sort for its level.
data class TaskOrder(
    val groups: List<String> = emptyList(),
    val children: Map<String, List<String>> = emptyMap(),
    val tasks: Map<String, List<String>> = emptyMap(),
) {
    fun forSibling(siblingKey: String): List<String> = when {
        siblingKey == GROUPS -> groups
        siblingKey.startsWith(PREFIX) -> children[siblingKey.removePrefix(PREFIX)].orEmpty()
        siblingKey.startsWith(TOPIC) -> tasks[siblingKey.removePrefix(TOPIC)].orEmpty()
        else -> emptyList()
    }

    fun withSibling(siblingKey: String, value: List<String>): TaskOrder = when {
        siblingKey == GROUPS -> copy(groups = value)
        siblingKey.startsWith(PREFIX) ->
            copy(children = children + (siblingKey.removePrefix(PREFIX) to value))
        siblingKey.startsWith(TOPIC) ->
            copy(tasks = tasks + (siblingKey.removePrefix(TOPIC) to value))
        else -> this
    }

    // Drops entries whose task, prefix, or topic no longer exists. Topic
    // entries are given as "prefix|topic".
    fun prune(
        validTasks: Set<String>,
        validPrefixes: Set<String>,
        validTopics: Set<String>,
    ): TaskOrder = copy(
        groups = groups.filter { it in validPrefixes },
        children = children.mapNotNull { (prefix, ids) ->
            if (prefix !in validPrefixes) return@mapNotNull null
            val kept = ids.filter { node ->
                when {
                    node.startsWith("k:") -> node.removePrefix("k:") in validTasks
                    node.startsWith("t:") -> "$prefix|${node.removePrefix("t:")}" in validTopics
                    else -> false
                }
            }
            if (kept.isEmpty()) null else prefix to kept
        }.toMap(),
        tasks = tasks.mapNotNull { (scope, ids) ->
            val kept = ids.filter { it in validTasks }
            if (kept.isEmpty()) null else scope to kept
        }.toMap(),
    )

    companion object {
        const val GROUPS = "groups"
        const val PREFIX = "p:"
        const val TOPIC = "t:"

        fun groupKey(prefix: String) = "$PREFIX$prefix"
        fun topicKey(prefix: String, topic: String) = "$TOPIC$prefix|$topic"
        fun taskId(id: String) = "k:$id"
        fun topicChild(topic: String) = "t:$topic"
    }
}

// Minimal JSON subset reader/writer for the order file. The document is
// written by this app only: an object with array fields and arrays-of-arrays
// maps, all values are strings.
object OrderFile {
    fun serialize(order: TaskOrder): String = buildString {
        append("{\n")
        append("  \"groups\": ").append(array(order.groups)).append(",\n")
        append("  \"children\": ").append(map(order.children)).append(",\n")
        append("  \"tasks\": ").append(map(order.tasks)).append("\n")
        append("}\n")
    }

    fun parse(text: String): TaskOrder {
        val value = runCatching { Json(text).readDocument() }.getOrNull() ?: return TaskOrder()
        val root = value as? Map<*, *> ?: return TaskOrder()
        return TaskOrder(
            groups = stringList(root["groups"]),
            children = arrayMap(root["children"]),
            tasks = arrayMap(root["tasks"]),
        )
    }

    private fun stringList(value: Any?): List<String> =
        (value as? List<*>)?.mapNotNull { it as? String }.orEmpty()

    private fun arrayMap(value: Any?): Map<String, List<String>> {
        val map = value as? Map<*, *> ?: return emptyMap()
        return map.entries.mapNotNull { (k, v) ->
            val key = k as? String ?: return@mapNotNull null
            key to stringList(v)
        }.toMap()
    }

    private fun array(values: List<String>): String =
        values.joinToString(prefix = "[", postfix = "]") { "\"" + escape(it) + "\"" }

    private fun map(values: Map<String, List<String>>): String =
        values.entries.joinToString(prefix = "{", postfix = "}") { (k, v) ->
            "\"" + escape(k) + "\": " + array(v)
        }

    private fun escape(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"")

    private class Json(private val text: String) {
        private var index = 0

        fun readDocument(): Any? {
            val value = readValue()
            skipWhitespace()
            return value
        }

        private fun readValue(): Any? {
            skipWhitespace()
            if (index >= text.length) return null
            return when (text[index]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> readString()
                'n' -> { expect("null"); null }
                else -> null
            }
        }

        private fun readObject(): Map<String, Any?> {
            val result = LinkedHashMap<String, Any?>()
            index++ // {
            skipWhitespace()
            if (peek() == '}') { index++; return result }
            while (index < text.length) {
                skipWhitespace()
                val key = readString() ?: return result
                skipWhitespace()
                if (peek() != ':') return result
                index++
                result[key] = readValue()
                skipWhitespace()
                when (peek()) {
                    ',' -> index++
                    '}' -> { index++; return result }
                    else -> return result
                }
            }
            return result
        }

        private fun readArray(): List<Any?> {
            val result = ArrayList<Any?>()
            index++ // [
            skipWhitespace()
            if (peek() == ']') { index++; return result }
            while (index < text.length) {
                result.add(readValue())
                skipWhitespace()
                when (peek()) {
                    ',' -> index++
                    ']' -> { index++; return result }
                    else -> return result
                }
            }
            return result
        }

        private fun readString(): String? {
            skipWhitespace()
            if (peek() != '"') return null
            index++
            val out = StringBuilder()
            while (index < text.length) {
                when (val c = text[index]) {
                    '"' -> { index++; return out.toString() }
                    '\\' -> {
                        index++
                        when (val esc = text.getOrNull(index)) {
                            '"', '\\', '/' -> out.append(esc)
                            'n' -> out.append('\n')
                            't' -> out.append('\t')
                            else -> out.append(esc)
                        }
                        index++
                    }
                    else -> { out.append(c); index++ }
                }
            }
            return out.toString()
        }

        private fun peek(): Char? = text.getOrNull(index)

        private fun skipWhitespace() {
            while (index < text.length && text[index].isWhitespace()) index++
        }

        private fun expect(word: String) {
            if (text.startsWith(word, index)) index += word.length
        }
    }
}
