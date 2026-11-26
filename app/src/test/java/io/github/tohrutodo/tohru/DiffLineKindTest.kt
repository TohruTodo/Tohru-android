package io.github.tohrutodo.tohru

import org.junit.Assert.assertEquals
import org.junit.Test
import io.github.tohrutodo.tohru.ui.DiffLineKind
import io.github.tohrutodo.tohru.ui.diffLineKind

class DiffLineKindTest {
    @Test fun fileHeadersAreNotAddedOrRemovedLines() {
        assertEquals(DiffLineKind.Header, diffLineKind("+++ b/tasks/a.md"))
        assertEquals(DiffLineKind.Header, diffLineKind("--- a/tasks/a.md"))
        assertEquals(DiffLineKind.Header, diffLineKind("diff --git a/tasks/a.md b/tasks/a.md"))
        assertEquals(DiffLineKind.Header, diffLineKind("index 123..456 100644"))
    }

    @Test fun contentLinesAreClassified() {
        assertEquals(DiffLineKind.Added, diffLineKind("+new"))
        assertEquals(DiffLineKind.Removed, diffLineKind("-old"))
        assertEquals(DiffLineKind.Hunk, diffLineKind("@@ -1,2 +1,3 @@"))
        assertEquals(DiffLineKind.Context, diffLineKind(" unchanged"))
    }
}
