package io.github.tohrutodo.tohru.store

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import io.github.tohrutodo.tohru.io.AtomicFiles
import io.github.tohrutodo.tohru.task.OrderFile
import io.github.tohrutodo.tohru.task.ReadmeDoc
import io.github.tohrutodo.tohru.task.Task
import io.github.tohrutodo.tohru.task.TaskArchive
import io.github.tohrutodo.tohru.task.TaskFile
import io.github.tohrutodo.tohru.task.TaskLinkIndex
import io.github.tohrutodo.tohru.task.TaskLinks
import io.github.tohrutodo.tohru.task.TaskOrder
import io.github.tohrutodo.tohru.task.TaskParser
import io.github.tohrutodo.tohru.task.TaskTree

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes

sealed interface TaskStoreEvent {
    data class Failure(val cause: Throwable) : TaskStoreEvent
    data class InvalidFile(val fileName: String, val cause: Throwable? = null) : TaskStoreEvent
}

// Owns task files under <repo>/tasks plus the manual order file. Writes run on
// Dispatchers.IO. Git commits are deliberately left to the user.
class TaskStore(
    private val tasksDir: File,
    private val onChanged: suspend () -> Unit = {},
    private val cacheFile: File? = null,
) {
    private val orderFile = File(tasksDir, "_order.json")
    private var fileById: Map<String, File> = emptyMap()
    private val _tasks = MutableStateFlow(emptyList<Task>())
    val tasks: StateFlow<List<Task>> = _tasks.asStateFlow()

    private val _order = MutableStateFlow(TaskOrder())
    val order: StateFlow<TaskOrder> = _order.asStateFlow()

    private val _readmes = MutableStateFlow(emptyList<ReadmeDoc>())
    val readmes: StateFlow<List<ReadmeDoc>> = _readmes.asStateFlow()

    private val _links = MutableStateFlow(TaskLinkIndex())
    val links: StateFlow<TaskLinkIndex> = _links.asStateFlow()

    private val _events = MutableSharedFlow<TaskStoreEvent>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<TaskStoreEvent> = _events.asSharedFlow()

    private companion object {
        const val CACHE_MAGIC = 0x544F4852
        const val CACHE_VERSION = 1
    }

    private val moveMutex = Mutex()
    private val writeMutex = Mutex()
    private data class Loaded(
        val tasks: List<Task>,
        val order: TaskOrder,
        val filesById: Map<String, File>,
        val readmes: List<ReadmeDoc>,
    )

    private data class CachedTask(
        val task: Task,
        val targets: List<String>,
        val modified: Long,
        val size: Long,
        val fileKey: String?,
    )

    // Cache keys are paths relative to tasksDir, so two tasks with the same
    // leaf name in different groups stay distinct.
    private val fileCache = mutableMapOf<String, CachedTask>()
    private val targetsById = mutableMapOf<String, List<String>>()

    private fun fileKey(file: File): String? = runCatching {
        Files.readAttributes(file.toPath(), BasicFileAttributes::class.java).fileKey()?.toString()
    }.getOrNull()

    private fun readCache(): Map<String, CachedTask> {
        val file = cacheFile ?: return emptyMap()
        if (!file.isFile) return emptyMap()
        return runCatching {
            DataInputStream(file.inputStream().buffered()).use { input ->
                if (input.readInt() != CACHE_MAGIC || input.readInt() != CACHE_VERSION) {
                    return@use emptyMap()
                }
                if (input.readUtf8() != tasksDir.absolutePath) {
                    return@use emptyMap()
                }
                val count = input.readInt()
                val result = mutableMapOf<String, CachedTask>()
                repeat(count) {
                    val path = input.readUtf8()
                    val modified = input.readLong()
                    val size = input.readLong()
                    val fileKey = if (input.readBoolean()) input.readUtf8() else null
                    val task = Task(
                        id = input.readUtf8(),
                        title = input.readUtf8(),
                        body = input.readUtf8(),
                        done = input.readBoolean(),
                        archived = input.readBoolean(),
                        created = input.readLong(),
                        updated = input.readLong(),
                    )
                    val targets = List(input.readInt()) { input.readUtf8() }
                    result[path] = CachedTask(task, targets, modified, size, fileKey)
                }
                result
            }
        }.getOrDefault(emptyMap())
    }

    private fun writeCache() {
        val file = cacheFile ?: return
        runCatching {
            val out = ByteArrayOutputStream()
            DataOutputStream(out).use { output ->
                output.writeInt(CACHE_MAGIC)
                output.writeInt(CACHE_VERSION)
                output.writeUtf8(tasksDir.absolutePath)
                output.writeInt(fileCache.size)
                fileCache.forEach { (path, entry) ->
                    output.writeUtf8(path)
                    output.writeLong(entry.modified)
                    output.writeLong(entry.size)
                    output.writeBoolean(entry.fileKey != null)
                    entry.fileKey?.let { output.writeUtf8(it) }
                    output.writeUtf8(entry.task.id)
                    output.writeUtf8(entry.task.title)
                    output.writeUtf8(entry.task.body)
                    output.writeBoolean(entry.task.done)
                    output.writeBoolean(entry.task.archived)
                    output.writeLong(entry.task.created)
                    output.writeLong(entry.task.updated)
                    output.writeInt(entry.targets.size)
                    entry.targets.forEach { output.writeUtf8(it) }
                }
            }
            AtomicFiles.writeBytes(file, out.toByteArray())
        }
    }

    private fun DataOutputStream.writeUtf8(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readUtf8(): String {
        val bytes = ByteArray(readInt())
        readFully(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    suspend fun refresh() {
        val loaded = withContext(Dispatchers.IO) {
            tasksDir.mkdirs()
            val readmes = mutableListOf<ReadmeDoc>()
            val persisted = readCache()
            val files = tasksDir.walkTopDown()
                .filter { it.isFile && it.extension == "md" }
                .toList()
            val parsed = files.mapNotNull { file ->
                val relative = TaskFile.relativePath(tasksDir, file)
                if (file.name == ReadmeDoc.README_NAME) {
                    val body = runCatching { file.readText() }.getOrElse { error ->
                        reportFailure(error)
                        return@mapNotNull null
                    }
                    if (body.isBlank()) {
                        runCatching { AtomicFiles.writeBatch(mapOf(file to null)) }
                            .onFailure(::reportFailure)
                    } else {
                        readmes += ReadmeDoc(relative.split('/').dropLast(1), body)
                    }
                    return@mapNotNull null
                }
                val modified = file.lastModified()
                val size = file.length()
                val key = fileKey(file)
                (fileCache[relative] ?: persisted[relative])
                    ?.takeIf { it.modified == modified && it.size == size && it.fileKey == key }
                    ?.let {
                        fileCache[relative] = it
                        return@mapNotNull file to it
                    }
                val state = TaskFile.stateOf(relative)
                val text = runCatching { file.readText() }.getOrElse { error ->
                    fileCache.remove(relative)
                    _events.tryEmit(TaskStoreEvent.InvalidFile(relative, error))
                    return@mapNotNull null
                }
                val task = runCatching { TaskFile.deserialize(text, state) }
                val parsedTask = task.getOrNull()
                if (task.isFailure || parsedTask == null) {
                    fileCache.remove(relative)
                    _events.tryEmit(TaskStoreEvent.InvalidFile(relative, task.exceptionOrNull()))
                    null
                } else {
                    val entry = CachedTask(
                        task = parsedTask,
                        targets = TaskLinks.targets(parsedTask.body),
                        modified = modified,
                        size = size,
                        fileKey = key,
                    )
                    fileCache[relative] = entry
                    file to entry
                }
            }
            val byId = parsed
                .filter { (_, entry) -> entry.task.title.isNotBlank() }
                .groupBy { it.second.task.id }
            val chosen = linkedMapOf<String, Task>()
            val keptFiles = linkedMapOf<String, File>()

            byId.forEach { (id, copies) ->
                val keep = copies.maxWithOrNull(
                    compareBy<Pair<File, CachedTask>> { it.second.task.updated }
                        .thenBy { TaskFile.relativePath(tasksDir, it.first) },
                )!!
                if (copies.size == 1) {
                    chosen[id] = keep.second.task
                    keptFiles[id] = keep.first
                    return@forEach
                }
                val target = TaskFile.taskFile(tasksDir, keep.second.task)
                val written = runCatching {
                    if (target != keep.first) {
                        AtomicFiles.writeText(target, TaskFile.serialize(keep.second.task))
                    }
                }
                if (written.isFailure) {
                    reportFailure(written.exceptionOrNull()!!)
                    chosen[id] = keep.second.task
                    keptFiles[id] = keep.first
                } else {
                    val removed = runCatching {
                        copies.forEach { (file, _) ->
                            if (file != target && file.exists() && !file.delete()) {
                                error("cannot delete duplicate ${file.name}")
                            }
                        }
                    }
                    removed.exceptionOrNull()?.let(::reportFailure)
                    fileCache.remove(TaskFile.relativePath(tasksDir, target))
                    chosen[id] = keep.second.task
                    keptFiles[id] = target.takeIf { it.exists() } ?: keep.first
                }
            }

            // No in-app migration: a task is only accepted at the path implied
            // by its title and state. Everything else stays on disk untouched.
            val rejected = chosen.filter { (id, task) ->
                keptFiles.getValue(id).absoluteFile != TaskFile.taskFile(tasksDir, task).absoluteFile
            }
            rejected.forEach { (id, _) ->
                _events.tryEmit(
                    TaskStoreEvent.InvalidFile(TaskFile.relativePath(tasksDir, keptFiles.getValue(id))),
                )
            }
            rejected.keys.forEach { id ->
                chosen.remove(id)
                keptFiles.remove(id)
            }

            val targetsFor = chosen.mapValues { (id, task) ->
                val file = keptFiles.getValue(id)
                fileCache[TaskFile.relativePath(tasksDir, file)]?.targets
                    ?: TaskLinks.targets(task.body)
            }

            val tasks = chosen.values.sortedByDescending { it.updated }
            val order = if (orderFile.exists()) {
                runCatching { OrderFile.parse(orderFile.readText()) }.getOrDefault(TaskOrder())
            } else {
                TaskOrder()
            }
            val keptPaths = keptFiles.values
                .map { TaskFile.relativePath(tasksDir, it) }
                .toSet()
            fileCache.keys.retainAll(keptPaths)
            chosen.forEach { (id, task) ->
                val file = keptFiles.getValue(id)
                val targets = targetsFor[id].orEmpty()
                fileCache[TaskFile.relativePath(tasksDir, file)] = CachedTask(
                    task = task,
                    targets = targets,
                    modified = file.lastModified(),
                    size = file.length(),
                    fileKey = fileKey(file),
                )
            }
            writeCache()
            Loaded(tasks, order, keptFiles, readmes.sortedBy { it.path })
        }
        _tasks.value = loaded.tasks
        _order.value = loaded.order
        _readmes.value = loaded.readmes
        fileById = loaded.filesById
        targetsById.clear()
        loaded.tasks.forEach { task ->
            val file = loaded.filesById[task.id] ?: return@forEach
            targetsById[task.id] = fileCache[TaskFile.relativePath(tasksDir, file)]?.targets
                ?: TaskLinks.targets(task.body)
        }
        rebuildLinks()
    }

    // Creates or replaces one README. Segments are the folder path below
    // tasks; an empty list writes tasks/README.md.
    suspend fun saveReadme(segments: List<String>, body: String) {
        if (!validReadmeSegments(segments)) return
        if (body.isBlank()) {
            deleteReadme(segments)
            return
        }
        val target = File(tasksDir, (segments + ReadmeDoc.README_NAME).joinToString("/"))
        writeMutex.withLock {
            withContext(Dispatchers.IO) {
                tasksDir.mkdirs()
                AtomicFiles.writeText(target, body)
            }
        }
        _readmes.value = (_readmes.value.filterNot { it.segments == segments } + ReadmeDoc(segments, body))
            .sortedBy { it.path }
        notifyChanged()
    }

    suspend fun deleteReadme(segments: List<String>) {
        if (!validReadmeSegments(segments)) return
        val target = File(tasksDir, (segments + ReadmeDoc.README_NAME).joinToString("/"))
        writeMutex.withLock {
            withContext(Dispatchers.IO) {
                AtomicFiles.writeBatch(mapOf(target to null))
            }
        }
        _readmes.value = _readmes.value.filterNot { it.segments == segments }
        notifyChanged()
    }

    private fun validReadmeSegments(segments: List<String>): Boolean =
        segments.none { it.isBlank() || it == ".." || it.contains('/') || it.contains('\\') }

    private fun rebuildLinks() {
        val pathById = fileById.mapValues { (_, file) ->
            TaskFile.relativePath(tasksDir, file)
        }
        val fileToId = pathById.entries.associate { (id, path) ->
            path.substringAfterLast('/') to id
        }
        val referrers = mutableMapOf<String, MutableSet<String>>()
        targetsById.forEach { (id, targets) ->
            targets.forEach { target ->
                referrers.getOrPut(target) { mutableSetOf() } += id
            }
        }
        _links.value = TaskLinkIndex(pathById, fileToId, referrers)
    }

    // Applies a known mutation without rescanning the task directory. The
    // full refresh stays for startup and external changes.
    private fun applyTasks(updated: List<Task>, removedIds: Set<String> = emptySet()) {
        if (updated.isEmpty() && removedIds.isEmpty()) return
        val previousById = _tasks.value.associateBy { it.id }
        val byId = previousById.toMutableMap()
        updated.forEach { byId[it.id] = it }
        removedIds.forEach { byId.remove(it) }
        _tasks.value = byId.values.sortedByDescending { it.updated }

        val updatedIds = updated.map { it.id }.toSet()
        fileCache.entries.removeIf { (_, entry) ->
            entry.task.id in updatedIds || entry.task.id in removedIds
        }
        updated.forEach { task ->
            val targets = if (previousById[task.id]?.body == task.body) {
                targetsById[task.id] ?: TaskLinks.targets(task.body)
            } else {
                TaskLinks.targets(task.body)
            }
            targetsById[task.id] = targets
            val file = fileById[task.id] ?: return@forEach
            fileCache[TaskFile.relativePath(tasksDir, file)] = CachedTask(
                task = task,
                targets = targets,
                modified = file.lastModified(),
                size = file.length(),
                fileKey = fileKey(file),
            )
        }
        removedIds.forEach { id ->
            targetsById.remove(id)
            fileById = fileById - id
        }
        rebuildLinks()
    }

    suspend fun save(task: Task) {
        if (task.title.isBlank()) return
        val current = _tasks.value.firstOrNull { it.id == task.id }
        if (current != null &&
            current.title == task.title &&
            current.body == task.body &&
            current.done == task.done &&
            current.archived == task.archived &&
            current.created == task.created
        ) {
            return
        }
        val stamped = task.copy(updated = System.currentTimeMillis())
        try {
            val written = writeTasks(listOf(stamped))
            applyTasks(written)
            notifyChanged()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            reportFailure(error)
        }
    }

    suspend fun toggle(id: String) {
        val task = _tasks.value.firstOrNull { it.id == id } ?: return
        val markDone = !task.done
        // Completion is not activity: keep the old timestamp so the task
        // returns to the same position when reopened.
        val updated = task.copy(done = markDone)
        val written = writeTasks(listOf(updated))
        applyTasks(written)
        notifyChanged()
    }

    suspend fun archive(id: String) = setArchived(listOf(id), true)

    suspend fun restore(id: String) = setArchived(listOf(id), false)

    suspend fun setArchived(ids: Collection<String>, archived: Boolean) {
        val targets = _tasks.value.filter { it.id in ids }
        if (targets.isEmpty()) return
        val updated = targets.map { it.copy(archived = archived) }
        val written = writeTasks(updated)
        applyTasks(written)
        notifyChanged()
    }

    // Bulk done state for a selection: one write pass, then completed items
    // join the top of their done block like a single toggle.
    suspend fun setDone(ids: Collection<String>, done: Boolean) {
        val targets = _tasks.value.filter { it.id in ids }
        if (targets.isEmpty()) return
        val updated = targets.map { it.copy(done = done) }
        val written = writeTasks(updated)
        applyTasks(written)
        notifyChanged()
    }

    // Permanent removal. Callers must confirm with the user first.
    suspend fun remove(id: String) = removeAll(listOf(id))

    suspend fun removeAll(ids: Collection<String>) {
        if (ids.isEmpty()) return
        writeMutex.withLock {
            withContext(Dispatchers.IO) {
                val changes = ids.mapNotNull { id -> fileById[id]?.let { it to null } }.toMap()
                AtomicFiles.writeBatch(changes)
            }
        }
        applyTasks(emptyList(), ids.toSet())
        notifyChanged()
    }

    // Moves a node inside its own level. Levels are never mixed, so the
    // sibling list defines both the source and the target position.
    suspend fun move(siblingKey: String, movedNodeKey: String, beforeNodeKey: String?) = moveMutex.withLock {
        moveLocked(siblingKey, movedNodeKey, beforeNodeKey)
    }

    private suspend fun moveLocked(siblingKey: String, movedNodeKey: String, beforeNodeKey: String?) {
        val movedId = storageId(siblingKey, movedNodeKey)
        val beforeId = beforeNodeKey?.let { storageId(siblingKey, it) }
        val current = currentSiblingOrder(siblingKey).toMutableList()
        if (movedId !in current) return
        current.remove(movedId)
        val targetIndex = beforeId?.let { current.indexOf(it) }?.takeIf { it >= 0 } ?: current.size
        current.add(targetIndex, movedId)
        val updated = _order.value.withSibling(siblingKey, current)
        persistOrder(updated)
        notifyChanged()
    }

    // UI rows use prefixed node keys; the order file stores bare ids.
    private fun storageId(siblingKey: String, nodeKey: String): String = when {
        siblingKey == TaskOrder.GROUPS -> nodeKey.removePrefix(TaskOrder.PREFIX)
        siblingKey.startsWith(TaskOrder.PREFIX) && nodeKey.startsWith("t:") ->
            "t:" + nodeKey.removePrefix("t:").substringAfter('|')
        siblingKey.startsWith(TaskOrder.TOPIC) && nodeKey.startsWith("k:") ->
            nodeKey.removePrefix("k:")
        else -> nodeKey
    }

    // Drops every stored position; the automatic activity sort returns.
    suspend fun clearAllOrder() {
        persistOrder(TaskOrder())
        notifyChanged()
    }

    // Export/commit callers use this to wait for a write burst to settle.
    suspend fun flushWrites() {
        writeMutex.withLock { }
    }

    suspend fun exportArchive(target: File): Int = withContext(Dispatchers.IO) {
        flushWrites()
        TaskArchive.export(tasksDir, target)
    }

    suspend fun importArchive(zip: File, mode: TaskArchive.MergeMode): Int {
        val imported = withContext(Dispatchers.IO) { TaskArchive.read(zip) }
        val incomingTasks = imported.tasks.filter { it.title.isNotBlank() }
        val current = _tasks.value.associateBy { it.id }
        val outgoing = incomingTasks.mapNotNull { incoming ->
            when {
                incoming.id !in current -> incoming
                mode == TaskArchive.MergeMode.SKIP -> null
                mode == TaskArchive.MergeMode.OVERWRITE -> incoming
                else -> incoming.copy(
                    id = TaskFile.newId(),
                    created = System.currentTimeMillis(),
                    updated = System.currentTimeMillis(),
                )
            }
        }
        if (outgoing.isNotEmpty()) {
            val written = writeTasks(outgoing)
            applyTasks(written)
        }
        importReadmes(imported.readmes, mode)
        if (_order.value == TaskOrder()) {
            imported.orderText?.let { text ->
                runCatching { OrderFile.parse(text) }.getOrNull()?.let { persistOrder(it) }
            }
        }
        notifyChanged()
        return outgoing.size
    }

    private suspend fun importReadmes(readmes: List<ReadmeDoc>, mode: TaskArchive.MergeMode) {
        if (readmes.isEmpty()) return
        val existing = _readmes.value.map { it.segments }.toSet()
        val incoming = if (mode == TaskArchive.MergeMode.SKIP) {
            readmes.filterNot { it.segments in existing }
        } else {
            readmes
        }
        if (incoming.isEmpty()) return
        writeMutex.withLock {
            withContext(Dispatchers.IO) {
                tasksDir.mkdirs()
                AtomicFiles.writeBatch(
                    incoming.associate { doc ->
                        File(tasksDir, doc.path) to doc.body.toByteArray(Charsets.UTF_8)
                    },
                )
            }
        }
        val incomingSegments = incoming.map { it.segments }.toSet()
        _readmes.value = (_readmes.value.filterNot { it.segments in incomingSegments } + incoming)
            .sortedBy { it.path }
    }

    private fun pruned(order: TaskOrder): TaskOrder {
        val tasks = _tasks.value
        val ids = tasks.map { it.id }.toSet()
        val prefixes = tasks.map { it.parsed.prefix ?: TaskParser.INBOX }.toSet()
        val topics = tasks.mapNotNull { task ->
            val parsed = task.parsed
            val topic = parsed.topic ?: return@mapNotNull null
            "${parsed.prefix}|$topic"
        }.toSet()
        return order.prune(ids, prefixes, topics)
    }

    // Mirrors the rendered order: siblings missing from the stored order come
    // first, the rest keep their saved positions. Prefix levels are normalised
    // so plain tasks sit above topic blocks, matching what the tree renders.
    private fun currentSiblingOrder(siblingKey: String): List<String> {
        val stored = _order.value.forSibling(siblingKey)
        val automatic = TaskTree.autoSiblingOrder(_tasks.value, siblingKey)
        val merged = if (stored.isEmpty()) {
            automatic
        } else {
            val known = stored.filter { it in automatic }.distinct()
            val knownSet = known.toSet()
            automatic.filter { it !in knownSet } + known
        }
        return TaskTree.blockOrder(siblingKey, merged)
    }

    private suspend fun persistOrder(order: TaskOrder) {
        val pruned = pruned(order)
        _order.value = pruned
        writeMutex.withLock {
            withContext(Dispatchers.IO) {
                tasksDir.mkdirs()
                AtomicFiles.writeText(orderFile, OrderFile.serialize(pruned))
            }
        }
    }

    // Rewrites files whose path changes and every task that links to them,
    // then writes them all in one batch.
    private suspend fun writeTasks(tasks: List<Task>): List<Task> {
        if (tasks.isEmpty()) return emptyList()
        var written: List<Task> = emptyList()
        writeMutex.withLock {
            withContext(Dispatchers.IO) {
                tasksDir.mkdirs()
                val planned = planWrites(tasks)
                val previous = _tasks.value.associateBy { it.id }
                val changes = linkedMapOf<File, ByteArray?>()
                val moves = mutableListOf<Pair<File, File>>()
                planned.forEach { task ->
                    val target = TaskFile.taskFile(tasksDir, task)
                    val old = fileById[task.id]
                    val unchanged = previous[task.id]?.let { before ->
                        before.title == task.title &&
                            before.body == task.body &&
                            before.created == task.created &&
                            before.updated == task.updated
                    } == true
                    // State-only changes move the stored bytes; any field or
                    // link change rewrites the file.
                    if (old != null && old != target && old.isFile && !target.exists() && unchanged) {
                        moves += old to target
                    } else {
                        old?.takeIf { it != target }?.let { changes[it] = null }
                        changes[target] = TaskFile.serialize(task).toByteArray(Charsets.UTF_8)
                    }
                }
                AtomicFiles.writeBatch(changes)
                AtomicFiles.moveBatch(moves)
                fileById = fileById + planned.associate { it.id to TaskFile.taskFile(tasksDir, it) }
                written = planned
            }
        }
        return written
    }

    // Resolves the new paths of this batch before touching the disk, so link
    // rewriting sees final destinations even when both ends move together.
    private fun planWrites(updated: List<Task>): List<Task> {
        val byId = linkedMapOf<String, Task>()
        updated.forEach { byId[it.id] = it }

        val pathById = _links.value.pathById.toMutableMap()
        val oldPaths = updated.associate { task ->
            task.id to (pathById[task.id]
                ?: fileById[task.id]?.let { TaskFile.relativePath(tasksDir, it) })
        }
        val newPaths = updated.associate { it.id to TaskFile.relativePath(it) }
        newPaths.forEach { (id, path) -> pathById[id] = path }
        val pathByLeaf = pathById.entries.associate { (_, path) ->
            path.substringAfterLast('/') to path
        }.toMutableMap()
        // A moved task that links to itself still resolves through the old
        // leaf, which no longer appears in pathById.
        oldPaths.forEach { (id, oldPath) ->
            if (oldPath != null) {
                val leaf = oldPath.substringAfterLast('/')
                if (leaf !in pathByLeaf) pathByLeaf[leaf] = newPaths.getValue(id)
            }
        }

        // A task's own relative links depend on its directory. Unsaved drafts
        // were written from the tasks root, so an empty path is the old base.
        updated.forEach { task ->
            val oldPath = oldPaths[task.id] ?: ""
            val newPath = newPaths.getValue(task.id)
            if (oldPath.substringBeforeLast('/', "") == newPath.substringBeforeLast('/', "")) {
                return@forEach
            }
            val current = byId.getValue(task.id)
            val body = TaskLinks.rebase(current.body, newPath, pathByLeaf)
            if (body != current.body) byId[task.id] = current.copy(body = body)
        }

        // Every referrer follows its target to the new relative path.
        updated.forEach { task ->
            val oldPath = oldPaths[task.id] ?: return@forEach
            val newPath = newPaths.getValue(task.id)
            if (oldPath == newPath) return@forEach
            val oldLeaf = oldPath.substringAfterLast('/')
            val oldTitle = _tasks.value.firstOrNull { it.id == task.id }?.title ?: task.title
            _links.value.referrers[oldLeaf].orEmpty().forEach { refId ->
                if (refId == task.id) return@forEach
                val referrer = byId[refId] ?: _tasks.value.firstOrNull { it.id == refId }
                val sourcePath = pathById[refId] ?: return@forEach
                if (referrer == null) return@forEach
                val body = TaskLinks.rewrite(
                    body = referrer.body,
                    oldLeaf = oldLeaf,
                    newPath = newPath,
                    sourcePath = sourcePath,
                    oldTitle = oldTitle,
                    newTitle = task.title,
                )
                if (body != referrer.body) byId[refId] = referrer.copy(body = body)
            }
        }
        return byId.values.toList()
    }

    // Blank titles and malformed files stay on disk. They are excluded from
    // the loaded task set and never moved into another directory.
    private fun reportFailure(error: Throwable) {
        _events.tryEmit(TaskStoreEvent.Failure(error))
    }

    private suspend fun notifyChanged() {
        try {
            onChanged()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            reportFailure(error)
        }
    }
}
