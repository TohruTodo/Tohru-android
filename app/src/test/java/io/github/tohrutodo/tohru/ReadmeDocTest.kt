package io.github.tohrutodo.tohru

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import io.github.tohrutodo.tohru.task.ReadmeDoc
import io.github.tohrutodo.tohru.task.Task
import io.github.tohrutodo.tohru.task.readmeLabels

class ReadmeDocTest {
    @Test fun pathAndLabelFollowTheFolder() {
        val root = ReadmeDoc(emptyList(), "")
        assertEquals("README.md", root.path)
        assertEquals("Repo", root.label)

        val topic = ReadmeDoc(listOf("nhóm_công_việc", "báo_cáo"), "")
        assertEquals("nhóm_công_việc/báo_cáo/README.md", topic.path)
        assertEquals("báo cáo", topic.label)
    }

    @Test fun labelsIncludeParentGroupAndTaskNames() {
        val tasks = listOf(
            Task(id = "1-0001", title = "tohru: việc"),
            Task(id = "2-0002", title = "tohru(đồng bộ): việc"),
        )
        assertEquals(listOf("Repo"), readmeLabels(emptyList(), tasks))
        assertEquals(listOf("tohru"), readmeLabels(listOf("tohru"), tasks))
        assertEquals(listOf("tohru", "đồng bộ"), readmeLabels(listOf("tohru", "đồng_bộ"), tasks))
        assertEquals(listOf("lạ"), readmeLabels(listOf("lạ"), tasks))
    }

    @Test fun matchesTitlePathAndBody() {
        val doc = ReadmeDoc(listOf("tohru"), "Đồng bộ dùng JGit")
        assertTrue(doc.matches("tohru"))
        assertTrue(doc.matches("đồng bộ"))
        assertTrue(doc.matches("jgit"))
        assertTrue(doc.matches("  "))
        assertFalse(doc.matches("không có"))
    }
}
