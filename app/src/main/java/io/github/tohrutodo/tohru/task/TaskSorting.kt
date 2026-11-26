package io.github.tohrutodo.tohru.task

import java.text.Collator
import java.util.Locale

// Alphabetical ordering for the manage tab: open items first, then done,
// each block A-Z with Vietnamese collation.
object TaskSorting {
    private fun collator(): Collator = Collator.getInstance(Locale.forLanguageTag("vi-VN")).apply {
        strength = Collator.PRIMARY
    }

    fun alphabetical(tasks: List<Task>): List<Task> {
        val collator = collator()
        return tasks.sortedWith(
            compareBy<Task> { if (it.done) 1 else 0 }
                .thenComparator { left, right ->
                    collator.compare(left.parsed.body, right.parsed.body)
                },
        )
    }

    // Chips follow the board order. Prefixes whose every task is done move
    // to the end, but keep their relative order.
    fun prunePrefixFilter(
        selected: Set<String>,
        tasks: List<Task>,
        order: TaskOrder = TaskOrder(),
        excludedPrefixes: Set<String> = emptySet(),
    ): Set<String> {
        val valid = prefixes(tasks, order, excludedPrefixes).toSet()
        return selected.filterTo(mutableSetOf()) { it in valid }
    }

    fun prefixes(
        tasks: List<Task>,
        order: TaskOrder = TaskOrder(),
        excludedPrefixes: Set<String> = emptySet(),
    ): List<String> {
        val scoped = tasks.filterNot { it.archived }
        val ordered = TaskTree.orderedPrefixes(scoped, order)
            .filterNot { it in excludedPrefixes }
        val withOpen = ordered.filter { prefix ->
            scoped.any { it.parsed.prefix == prefix && !it.done }
        }
        val doneOnly = ordered.filter { prefix ->
            val group = scoped.filter { it.parsed.prefix == prefix }
            group.isNotEmpty() && group.all { it.done }
        }
        return withOpen + doneOnly
    }
}
