package io.github.tohrutodo.tohru

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import io.github.tohrutodo.tohru.task.Task
import io.github.tohrutodo.tohru.task.TaskEditor

class TaskEditorTest {
    @Test fun unchangedDraftIsNotDirty() {
        val loaded = Task(id = "x", title = "a", body = "b")
        assertFalse(TaskEditor.needsSave(loaded, "a", "b"))
        assertFalse(TaskEditor.needsSave(loaded, " a ", "b"))
        assertTrue(TaskEditor.needsSave(loaded, "a2", "b"))
        assertTrue(TaskEditor.needsSave(loaded, "a", "b\nc"))
        assertFalse(TaskEditor.needsSave(null, "a", "b"))
    }

    @Test fun blankTitleIsNeverSaved() {
        assertFalse(TaskEditor.shouldSave(null, "", "body", false))
        assertFalse(TaskEditor.shouldSave(null, "   ", "body", true))
        val loaded = Task(id = "x", title = "a", body = "b", done = true)
        assertFalse(TaskEditor.shouldSave(loaded, "  ", "b", true))
    }

    @Test fun newTaskSavesWhenTitlePresent() {
        assertTrue(TaskEditor.shouldSave(null, " a ", "", false))
        assertTrue(TaskEditor.shouldSave(null, "a", "body\n", true))
    }

    @Test fun existingTaskSavesOnlyWhenSomethingChanged() {
        val loaded = Task(id = "x", title = "a", body = "b", done = false)
        assertFalse(TaskEditor.shouldSave(loaded, "a", "b", false))
        assertFalse(TaskEditor.shouldSave(loaded, " a ", "b", false))
        assertTrue(TaskEditor.shouldSave(loaded, "a", "b", true))
        assertTrue(TaskEditor.shouldSave(loaded, "a", "b\nc", false))
        assertTrue(TaskEditor.shouldSave(loaded, "a2", "b", false))
    }

    @Test fun draftTrimsTitleOnly() {
        val loaded = Task(id = "x", title = "a")
        val draft = TaskEditor.draft(loaded, "  new title  ", "body\n")
        assertEquals("new title", draft.title)
        assertEquals("body\n", draft.body)
    }
}
