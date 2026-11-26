package io.github.tohrutodo.tohru

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import io.github.tohrutodo.tohru.task.TaskLinks
import io.github.tohrutodo.tohru.ui.withTaskLabels

class MarkdownTest {
    private fun linked(url: String, label: String): AnnotatedString = buildAnnotatedString {
        append("see ")
        withLink(LinkAnnotation.Url(url)) { append(label) }
    }

    @Test fun missingTaskUsesFileNotFoundLabel() {
        val text = linked("1-0001.md", "một").withTaskLabels { false }
        val link = text.getLinkAnnotations(0, text.length).single()
        assertEquals("see file not found", text.text)
        assertEquals("1-0001.md", (link.item as LinkAnnotation.Url).url)
    }

    @Test fun existingTaskKeepsLabel() {
        val text = linked("1-0001.md", "một").withTaskLabels { true }
        assertEquals("see một", text.text)
    }

    @Test fun externalLinkKeepsLabel() {
        val text = linked("https://example.com/a.md", "web").withTaskLabels { false }
        assertEquals("see web", text.text)
    }

    @Test fun linksWithoutResolverStayUntouched() {
        val text = linked("1-0001.md", "một").withTaskLabels(null)
        assertEquals("see một", text.text)
    }

    @Test fun absoluteMarkdownUrlsAreNotTaskLinks() {
        assertNull(TaskLinks.normalize("https://example.com/a.md"))
        assertEquals("a.md", TaskLinks.normalize("./a.md"))
    }
}
