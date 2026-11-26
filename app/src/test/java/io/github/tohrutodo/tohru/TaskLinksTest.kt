package io.github.tohrutodo.tohru

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.text.Normalizer
import io.github.tohrutodo.tohru.task.TaskLinks

class TaskLinksTest {
    @Test fun normalizeResolvesTheLeafAndNormalizesToNfc() {
        val decomposed = Normalizer.normalize("nhóm.md", Normalizer.Form.NFD)
        assertEquals("nhóm.md", TaskLinks.normalize("../a/$decomposed"))
        assertEquals("nhóm.md", TaskLinks.normalize("<../a/nhóm.md>"))
        assertNull(TaskLinks.normalize("https://example.com/x.md"))
        assertNull(TaskLinks.normalize("../a/notes.txt"))
    }

    @Test fun targetsReadNestedRelativePaths() {
        val body = "See [T](../other/t-1_one.md) and [U](_done/u-2_two.md)."
        assertEquals(listOf("t-1_one.md", "u-2_two.md"), TaskLinks.targets(body))
    }

    @Test fun rewriteUsesTheSourceDirectory() {
        val body = "See [Target](old_target.md)"
        val rewritten = TaskLinks.rewrite(
            body = body,
            oldLeaf = "old_target.md",
            newPath = "_done/old_target.md",
            sourcePath = "group/source.md",
            oldTitle = "Target",
            newTitle = "Target",
        )
        assertEquals("See [Target](../_done/old_target.md)", rewritten)
    }

    @Test fun rewriteFollowsATitleChangeOnlyForTheDefaultLabel() {
        val body = "[Old](old_target.md) and [custom](old_target.md)"
        val rewritten = TaskLinks.rewrite(
            body = body,
            oldLeaf = "old_target.md",
            newPath = "_done/new_target.md",
            sourcePath = "source.md",
            oldTitle = "Old",
            newTitle = "New",
        )
        assertEquals("[New](_done/new_target.md) and [custom](_done/new_target.md)", rewritten)
    }

    @Test fun rebaseRewritesOwnLinksFromTheNewDirectory() {
        val body = "See [A](a-1_one.md) and [B](_done/b-2_two.md)"
        val paths = mapOf(
            "a-1_one.md" to "a-1_one.md",
            "b-2_two.md" to "_done/b-2_two.md",
        )
        val rewritten = TaskLinks.rebase(body, "group/source.md", paths)
        assertEquals("See [A](../a-1_one.md) and [B](../_done/b-2_two.md)", rewritten)
    }
}
