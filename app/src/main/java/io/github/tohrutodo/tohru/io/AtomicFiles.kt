package io.github.tohrutodo.tohru.io

import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

// Small same-directory atomic writer. Batch changes are staged as temp files
// and restored from an in-memory backup if any commit step fails.
object AtomicFiles {
    fun writeText(target: File, text: String) =
        writeBytes(target, text.toByteArray(Charsets.UTF_8))

    fun writeBytes(target: File, bytes: ByteArray) =
        writeBatch(mapOf(target to bytes))

    fun moveInto(source: File, target: File) = move(source, target)

    // Moves each pair in order. A failure moves the completed pairs back.
    fun moveBatch(moves: List<Pair<File, File>>) {
        val done = mutableListOf<Pair<File, File>>()
        try {
            moves.forEach { (source, target) ->
                if (source == target) return@forEach
                target.parentFile?.mkdirs()
                move(source, target)
                done += source to target
            }
        } catch (error: Throwable) {
            done.asReversed().forEach { (source, target) ->
                runCatching {
                    source.parentFile?.mkdirs()
                    move(target, source)
                }
            }
            throw error
        }
    }

    fun writeBatch(changes: Map<File, ByteArray?>) {
        if (changes.isEmpty()) return
        val backups = changes.keys.associateWith { file ->
            if (file.isFile) file.readBytes() else null
        }
        val temps = mutableMapOf<File, File>()
        try {
            changes.forEach { (target, bytes) ->
                if (bytes == null) return@forEach
                target.parentFile?.mkdirs()
                val temp = File.createTempFile(".${target.name}.", ".tmp", target.parentFile)
                FileOutputStream(temp).use { output ->
                    output.write(bytes)
                    output.fd.sync()
                }
                temps[target] = temp
            }
            changes.forEach { (target, bytes) ->
                if (bytes == null) {
                    if (target.exists() && !target.delete()) {
                        error("cannot delete ${target.name}")
                    }
                } else {
                    move(temps.getValue(target), target)
                }
            }
        } catch (error: Throwable) {
            changes.keys.forEach { target ->
                val backup = backups[target]
                runCatching {
                    if (backup == null) {
                        if (target.exists()) target.delete()
                    } else {
                        target.parentFile?.mkdirs()
                        target.writeBytes(backup)
                    }
                }
            }
            throw error
        } finally {
            temps.values.forEach { it.delete() }
        }
    }

    private fun move(source: File, target: File) {
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
