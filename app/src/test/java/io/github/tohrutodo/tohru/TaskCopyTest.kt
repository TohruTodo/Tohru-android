package io.github.tohrutodo.tohru

import org.junit.Assert.assertEquals
import org.junit.Test
import io.github.tohrutodo.tohru.task.Task
import io.github.tohrutodo.tohru.task.TaskCopy

class TaskCopyTest {
    private fun task(title: String, done: Boolean = false, body: String = "") =
        Task(id = title, title = title, done = done, body = body)

    @Test fun openTaskUsesUncheckedMarker() {
        assertEquals("- [ ] one", TaskCopy.format(listOf(task("one"))))
    }

    @Test fun doneTaskUsesCheckedMarker() {
        assertEquals("- [x] one", TaskCopy.format(listOf(task("one", done = true))))
    }

    @Test fun bodyLinesAreIndented() {
        assertEquals(
            "- [ ] one\n  line a\n  line b",
            TaskCopy.format(listOf(task("one", body = "line a\nline b"))),
        )
    }

    @Test fun blankBodyLineStaysUnindented() {
        assertEquals(
            "- [ ] one\n  first\n\n  second",
            TaskCopy.format(listOf(task("one", body = "first\n\nsecond\n"))),
        )
    }

    @Test fun tasksAreSeparatedByOneBlankLine() {
        assertEquals(
            "- [ ] one\n\n- [x] two\n  body",
            TaskCopy.format(listOf(task("one"), task("two", done = true, body = "body"))),
        )
    }

    @Test fun emptySelectionIsEmpty() {
        assertEquals("", TaskCopy.format(emptyList()))
    }
}
