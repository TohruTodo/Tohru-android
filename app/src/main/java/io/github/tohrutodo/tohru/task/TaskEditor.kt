package io.github.tohrutodo.tohru.task

// Decides whether an edited draft differs from the task on disk.
object TaskEditor {
    fun needsSave(loaded: Task?, title: String, body: String): Boolean {
        if (loaded == null) return false
        return loaded.title != title.trim() || loaded.body != body
    }

    fun draft(loaded: Task, title: String, body: String): Task =
        loaded.copy(title = title.trim(), body = body)
}
