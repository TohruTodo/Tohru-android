package io.github.tohrutodo.tohru.task

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import io.github.tohrutodo.tohru.io.AtomicFiles

// Portable worktree snapshot. The ZIP mirrors the repository's `tasks/`
// directory, including the group, topic, done and archive folders. It never
// includes `.git` or credentials.
object TaskArchive {
    enum class MergeMode { SKIP, OVERWRITE, DUPLICATE }

    data class Imported(
        val tasks: List<Task>,
        val readmes: List<ReadmeDoc>,
        val orderText: String?,
    )

    fun export(tasksDir: File, target: File): Int {
        target.parentFile?.mkdirs()
        val temp = File.createTempFile(".${target.name}.", ".tmp", target.parentFile)
        var count = 0
        try {
            ZipOutputStream(FileOutputStream(temp)).use { zip ->
                tasksDir.walkTopDown()
                    .filter { it.isFile && it.extension == "md" }
                    .sortedBy { TaskFile.relativePath(tasksDir, it) }
                    .forEach { file ->
                        val path = TaskFile.relativePath(tasksDir, file)
                        zip.putNextEntry(ZipEntry("tasks/$path"))
                        file.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                        count++
                    }
                val order = File(tasksDir, "_order.json")
                if (order.isFile) {
                    zip.putNextEntry(ZipEntry("tasks/_order.json"))
                    order.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            AtomicFiles.moveInto(temp, target)
        } finally {
            temp.delete()
        }
        return count
    }

    fun read(zip: File): Imported {
        val tasks = mutableListOf<Task>()
        val readmes = mutableListOf<ReadmeDoc>()
        var orderText: String? = null
        ZipInputStream(FileInputStream(zip)).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                val name = entry.name
                if (entry.isDirectory || !name.startsWith("tasks/")) continue
                val leaf = name.removePrefix("tasks/")
                if (leaf.split('/').any { it.isEmpty() || it == ".." || it == "." } ||
                    leaf.startsWith("/") ||
                    leaf.contains('\\')
                ) {
                    continue
                }
                if (leaf == "_order.json") {
                    orderText = input.readBytes().toString(Charsets.UTF_8)
                } else if (leaf.endsWith(".md")) {
                    val text = input.readBytes().toString(Charsets.UTF_8)
                    if (leaf.substringAfterLast('/') == ReadmeDoc.README_NAME) {
                        readmes += ReadmeDoc(leaf.split('/').dropLast(1), text)
                    } else {
                        val state = TaskFile.stateOf(leaf)
                        runCatching { TaskFile.deserialize(text, state) }.getOrNull()?.let(tasks::add)
                    }
                }
                input.closeEntry()
            }
        }
        return Imported(tasks, readmes, orderText)
    }
}
