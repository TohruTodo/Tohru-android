package io.github.tohrutodo.tohru.task

import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.CancellationToken
import org.intellij.markdown.parser.MarkdownParser
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.text.Normalizer

// Relative Markdown task links point at another task file. IDs remain the
// stable identity for lookups; destinations are resolved through the leaf
// file name, which carries the task id.
data class TaskLinkIndex(
    val pathById: Map<String, String> = emptyMap(),
    val fileToId: Map<String, String> = emptyMap(),
    val referrers: Map<String, Set<String>> = emptyMap(),
) {
    fun path(id: String): String? = pathById[id]

    fun idFor(target: String): String? {
        val name = TaskLinks.normalize(target) ?: return null
        return fileToId[name]
    }

    // Destination for a link inserted while editing [fromId]. Null means the
    // task has no file yet; the link is then relative to the tasks root and
    // gets rebased on the first save.
    fun linkFrom(fromId: String?, toId: String): String? {
        val target = pathById[toId] ?: return null
        return TaskFile.link(fromId?.let(pathById::get), target)
    }
}

object TaskLinks {
    fun normalize(target: String): String? {
        val raw = target.trim().removeSurrounding("<", ">").substringBefore('#')
        if (raw.isEmpty()) return null
        // Task links are relative paths; an absolute URL is not a task even
        // when its path happens to end in `.md`.
        if (raw.contains("://") || raw.startsWith("mailto:")) return null
        val decoded = runCatching {
            URLDecoder.decode(raw, StandardCharsets.UTF_8.name())
        }.getOrDefault(raw)
        val leaf = decoded.substringAfterLast('/')
        val normalized = Normalizer.normalize(leaf, Normalizer.Form.NFC)
        return normalized.takeIf { it.endsWith(".md") && it.isNotBlank() }
    }

    fun targets(body: String): List<String> {
        val source = normalizeLines(body)
        val root = parse(source)
        val result = linkedSetOf<String>()
        walk(root) { node ->
            if (node.type == MarkdownElementTypes.LINK_DESTINATION) {
                normalize(source.substring(node.startOffset, node.endOffset))?.let(result::add)
            }
        }
        return result.toList()
    }

    // Repoints every destination that names [oldLeaf] to [newPath], written
    // relative to the referring file at [sourcePath]. A label equal to the old
    // title follows a title change; custom labels stay untouched.
    fun rewrite(
        body: String,
        oldLeaf: String,
        newPath: String,
        sourcePath: String?,
        oldTitle: String,
        newTitle: String,
    ): String {
        val source = normalizeLines(body)
        val root = parse(source)
        val replacement = TaskFile.link(sourcePath, newPath)
        val edits = mutableListOf<Edit>()
        walk(root) { node ->
            if (node.type != MarkdownElementTypes.LINK_DESTINATION) return@walk
            if (normalize(source.substring(node.startOffset, node.endOffset)) != oldLeaf) return@walk
            if (source.substring(node.startOffset, node.endOffset) != replacement) {
                edits += Edit(node.startOffset, node.endOffset, replacement)
            }
            if (oldTitle == newTitle) return@walk
            val label = node.parent?.children
                ?.firstOrNull { it.type == MarkdownElementTypes.LINK_TEXT }
                ?: return@walk
            if (source.substring(label.startOffset, label.endOffset) == "[$oldTitle]") {
                edits += Edit(label.startOffset, label.endOffset, "[${escapeLabel(newTitle)}]")
            }
        }
        return apply(body, source, edits)
    }

    // Rewrites the task's own destinations after its file directory changes.
    // Each destination is resolved by leaf name to the current path of its
    // target, then written relative to [sourcePath].
    fun rebase(body: String, sourcePath: String, pathByLeaf: Map<String, String>): String {
        val source = normalizeLines(body)
        val root = parse(source)
        val edits = mutableListOf<Edit>()
        walk(root) { node ->
            if (node.type != MarkdownElementTypes.LINK_DESTINATION) return@walk
            val leaf = normalize(source.substring(node.startOffset, node.endOffset)) ?: return@walk
            val target = pathByLeaf[leaf] ?: return@walk
            val replacement = TaskFile.link(sourcePath, target)
            if (source.substring(node.startOffset, node.endOffset) == replacement) return@walk
            edits += Edit(node.startOffset, node.endOffset, replacement)
        }
        return apply(body, source, edits)
    }

    fun escapeLabel(value: String): String =
        value.replace("\\", "\\\\").replace("[", "\\[").replace("]", "\\]")

    private fun parse(source: String): ASTNode =
        MarkdownParser(
            GFMFlavourDescriptor(),
            cancellationToken = CancellationToken.NonCancellable,
        ).buildMarkdownTreeFromString(source as CharSequence)

    private fun normalizeLines(value: String): String =
        value.replace("\r\n", "\n").replace('\r', '\n')

    private fun walk(node: ASTNode, visit: (ASTNode) -> Unit) {
        visit(node)
        node.children.forEach { walk(it, visit) }
    }

    private data class Edit(val start: Int, val end: Int, val text: String)

    private fun apply(body: String, source: String, edits: List<Edit>): String {
        if (edits.isEmpty()) return body
        var result = source
        edits.sortedByDescending { it.start }.forEach { edit ->
            result = result.replaceRange(edit.start, edit.end, edit.text)
        }
        // Parsing runs on LF-normalized text, so restore CRLF files unchanged
        // outside the rewritten spans.
        return if (body.contains("\r\n")) result.replace("\n", "\r\n") else result
    }
}
