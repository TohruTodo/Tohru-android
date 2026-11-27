package io.github.tohrutodo.tohru.task

// Decides whether an edited draft differs from the task on disk.
object TaskEditor {
    fun needsSave(loaded: Task?, title: String, body: String): Boolean {
        if (loaded == null) return false
        return loaded.title != title.trim() || loaded.body != body
    }

    // A blank title is discarded instead of stored, so the editor can close
    // without saving when the user leaves the composer empty.
    fun shouldSave(loaded: Task?, title: String, body: String, done: Boolean): Boolean {
        if (title.isBlank()) return false
        if (loaded == null) return true
        return needsSave(loaded, title, body) || loaded.done != done
    }

    fun draft(loaded: Task, title: String, body: String): Task =
        loaded.copy(title = title.trim(), body = body)
}
