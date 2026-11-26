package io.github.tohrutodo.tohru

import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.flavours.gfm.GFMTokenTypes
import org.intellij.markdown.parser.CancellationToken
import org.intellij.markdown.parser.MarkdownParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import io.github.tohrutodo.tohru.md.MarkdownDocument

class MarkdownDocumentTest {
    private val parser = MarkdownParser(
        GFMFlavourDescriptor(),
        cancellationToken = CancellationToken.NonCancellable,
    )

    private fun parse(content: String): ASTNode = parser.buildMarkdownTreeFromString(content)

    @Test fun headingsFlattenDeepLevels() {
        val doc = "# One\n#### Deep\n# Two\n###### Deeper"
        val headings = MarkdownDocument.headings(parse(doc), doc)
        assertEquals(listOf(1, 2, 1, 2), headings.map { it.depth })
        assertEquals(listOf(1, 4, 1, 6), headings.map { it.level })
        assertEquals(listOf("One", "Deep", "Two", "Deeper"), headings.map { it.text })
        assertEquals(listOf(0, 1, 2, 3), headings.map { it.line })
    }

    @Test fun headingsIgnoreHashtagsAndEmptyText() {
        val doc = "#hashtag\n    # indented\n## \nplain"
        assertTrue(MarkdownDocument.headings(parse(doc), doc).isEmpty())
    }

    @Test fun sectionsSplitAtHeadings() {
        val doc = "intro\n\n# One\na\n\n## Two\nb"
        val sections = MarkdownDocument.sections(parse(doc), doc)
        assertEquals(3, sections.size)
        assertNull(sections[0].heading)
        assertEquals("One", sections[1].heading?.text)
        assertEquals(2, sections[1].startLine)
        assertEquals("Two", sections[2].heading?.text)
        assertEquals(5, sections[2].startLine)
        assertTrue(sections[1].nodes.isNotEmpty())
    }

    @Test fun plainTextIsOneSection() {
        val doc = "just text\nline two"
        val sections = MarkdownDocument.sections(parse(doc), doc)
        assertEquals(1, sections.size)
        assertNull(sections[0].heading)
        assertEquals(0, sections[0].startLine)
    }

    @Test fun blankDocumentHasNoSections() {
        assertTrue(MarkdownDocument.sections(parse("   \n"), "   \n").isEmpty())
    }

    @Test fun headingLineTextKeepsMarkers() {
        val doc = "intro\n## Two\nb"
        assertEquals("## Two", MarkdownDocument.lineText(doc, 1))
    }

    @Test fun toggleCheckboxReplacesOnlyTheMarker() {
        val body = "- [ ] one\n- [x] two\n"
        val boxes = collect(parse(body), GFMTokenTypes.CHECK_BOX)
        assertEquals(2, boxes.size)
        assertEquals("[ ] ", body.substring(boxes[0].startOffset, boxes[0].endOffset))
        assertEquals("[x] ", body.substring(boxes[1].startOffset, boxes[1].endOffset))
        assertEquals("- [x] one\n- [x] two\n", MarkdownDocument.toggleCheckbox(body, boxes[0]))
        assertEquals("- [ ] one\n- [ ] two\n", MarkdownDocument.toggleCheckbox(body, boxes[1]))
    }

    @Test fun checkedListItemFollowsItsOwnMarker() {
        val body = "- [x] done\n- [ ] open\n"
        val root = parse(body)
        val items = collect(root, MarkdownElementTypes.LIST_ITEM)
        assertTrue(MarkdownDocument.checkedListItem(items[0], body))
        assertFalse(MarkdownDocument.checkedListItem(items[1], body))
    }

    private fun collect(node: ASTNode, type: org.intellij.markdown.IElementType): List<ASTNode> {
        val result = mutableListOf<ASTNode>()
        fun visit(current: ASTNode) {
            if (current.type == type) result += current
            current.children.forEach(::visit)
        }
        visit(node)
        return result
    }
}
