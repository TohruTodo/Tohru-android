package io.github.tohrutodo.tohru.md

import androidx.compose.ui.unit.sp
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.findChildOfType
import org.intellij.markdown.ast.getTextInNode
import org.intellij.markdown.flavours.gfm.GFMTokenTypes

data class MarkdownHeading(
    val level: Int,
    val depth: Int,
    val text: String,
    val line: Int,
)

data class MarkdownSection(
    val heading: MarkdownHeading?,
    val nodes: List<ASTNode>,
    val startLine: Int = 0,
)

// Reading-side projection of the parsed document: top-level nodes grouped at
// ATX headings so the outline can jump to a section, plus the small helpers
// the renderer and checklist need from the same AST.
object MarkdownDocument {
    fun headings(root: ASTNode, content: String): List<MarkdownHeading> {
        var depth = 0
        return root.children.mapNotNull { node ->
            val level = headingLevel(node) ?: return@mapNotNull null
            val text = headingText(node, content).ifEmpty { return@mapNotNull null }
            depth = minOf(level, depth + 1)
            MarkdownHeading(level = level, depth = depth, text = text, line = lineAt(content, node.startOffset))
        }
    }

    fun sections(root: ASTNode, content: String): List<MarkdownSection> {
        val result = mutableListOf<MarkdownSection>()
        var heading: MarkdownHeading? = null
        var nodes = mutableListOf<ASTNode>()
        var depth = 0
        fun flush() {
            if (nodes.isEmpty()) return
            val visible = nodes.any { node ->
                content.substring(node.startOffset, node.endOffset).isNotBlank()
            }
            if (visible) {
                result += MarkdownSection(
                    heading = heading,
                    nodes = nodes.toList(),
                    startLine = heading?.line ?: lineAt(content, nodes.first().startOffset),
                )
            }
        }
        root.children.forEach { node ->
            val level = headingLevel(node)
            if (level != null) {
                val text = headingText(node, content)
                if (text.isNotEmpty()) {
                    flush()
                    depth = minOf(level, depth + 1)
                    heading = MarkdownHeading(level, depth, text, lineAt(content, node.startOffset))
                    nodes = mutableListOf(node)
                    return@forEach
                }
            }
            nodes += node
        }
        flush()
        return result
    }

    fun lineAt(content: String, offset: Int): Int {
        val end = offset.coerceIn(0, content.length)
        var line = 0
        for (index in 0 until end) {
            if (content[index] == '\n') line++
        }
        return line
    }

    fun lineText(content: String, line: Int): String =
        content.lineSequence().elementAtOrNull(line).orEmpty()

    fun checkboxChecked(node: ASTNode, content: String): Boolean =
        node.getTextInNode(content).contains("[x]", ignoreCase = true)

    // Replaces the checkbox marker in place, so surrounding list indentation
    // and text stay byte-identical. The token span also covers the trailing
    // whitespace, so only the marker itself is replaced.
    fun toggleCheckbox(content: String, node: ASTNode): String {
        val span = content.substring(node.startOffset, node.endOffset)
        val checked = span.contains("[x]", ignoreCase = true)
        val marker = if (checked) "[x]" else "[ ]"
        val at = span.indexOf(marker, ignoreCase = true)
        if (at < 0) return content
        val start = node.startOffset + at
        return content.replaceRange(start, start + marker.length, if (checked) "[ ]" else "[x]")
    }

    // A list item is rendered struck through when its own marker is checked.
    fun checkedListItem(node: ASTNode, content: String): Boolean {
        var current: ASTNode? = node
        while (current != null) {
            if (current.type == MarkdownElementTypes.LIST_ITEM) {
                val box = current.children.firstOrNull { it.type == GFMTokenTypes.CHECK_BOX }
                return box != null && checkboxChecked(box, content)
            }
            current = current.parent
        }
        return false
    }

    fun headingSize(level: Int) = when (level) {
        1 -> 24.sp
        2 -> 20.sp
        3 -> 17.sp
        else -> 16.sp
    }

    fun headingLineHeight(level: Int) = when (level) {
        1 -> 30.sp
        2 -> 26.sp
        3 -> 22.sp
        else -> 20.sp
    }

    private fun headingLevel(node: ASTNode): Int? = when (node.type) {
        MarkdownElementTypes.ATX_1, MarkdownElementTypes.SETEXT_1 -> 1
        MarkdownElementTypes.ATX_2, MarkdownElementTypes.SETEXT_2 -> 2
        MarkdownElementTypes.ATX_3 -> 3
        MarkdownElementTypes.ATX_4 -> 4
        MarkdownElementTypes.ATX_5 -> 5
        MarkdownElementTypes.ATX_6 -> 6
        else -> null
    }

    private fun headingText(node: ASTNode, content: String): String {
        val type = if (headingLevel(node) == 1 || headingLevel(node) == 2) {
            node.findChildOfType(MarkdownTokenTypes.ATX_CONTENT)
                ?: node.findChildOfType(MarkdownTokenTypes.SETEXT_CONTENT)
        } else {
            node.findChildOfType(MarkdownTokenTypes.ATX_CONTENT)
        }
        return type?.getTextInNode(content)?.toString()?.trim().orEmpty()
    }
}
