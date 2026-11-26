package io.github.tohrutodo.tohru

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import io.github.tohrutodo.tohru.sync.DiffKind
import io.github.tohrutodo.tohru.sync.TextDiff

class TextDiffTest {
    @Test fun identicalTextHasNoDiff() {
        assertTrue(TextDiff.unified("a\nb\nc", "a\nb\nc").isEmpty())
    }

    @Test fun replacementShowsBothSides() {
        val lines = TextDiff.unified("a\nb\nc", "a\nB\nc")

        assertEquals(DiffKind.CONTEXT, lines.first().kind)
        assertTrue(lines.any { it.kind == DiffKind.REMOVED && it.text == "b" })
        assertTrue(lines.any { it.kind == DiffKind.ADDED && it.text == "B" })
    }

    @Test fun insertionAddsOnlyRemoteLine() {
        val lines = TextDiff.unified("a\nc", "a\nb\nc")

        assertTrue(lines.any { it.kind == DiffKind.ADDED && it.text == "b" })
        assertTrue(lines.none { it.kind == DiffKind.REMOVED })
    }

    @Test fun deletionShowsLocalLine() {
        val lines = TextDiff.unified("a\nb\nc", "a\nc")

        assertTrue(lines.any { it.kind == DiffKind.REMOVED && it.text == "b" })
        assertTrue(lines.none { it.kind == DiffKind.ADDED })
    }

    @Test fun contextIsLimitedAroundTheChange() {
        val base = (1..20).joinToString("\n") { "line $it" }
        val changed = (1..20).joinToString("\n") { if (it == 10) "changed" else "line $it" }

        val lines = TextDiff.unified(base, changed, context = 2)

        assertEquals(2, lines.takeWhile { it.kind == DiffKind.CONTEXT }.size)
        assertEquals(2, lines.takeLastWhile { it.kind == DiffKind.CONTEXT }.size)
    }
}
