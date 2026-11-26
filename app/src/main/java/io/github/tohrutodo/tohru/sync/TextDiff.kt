package io.github.tohrutodo.tohru.sync

import org.eclipse.jgit.diff.HistogramDiff
import org.eclipse.jgit.diff.RawText
import org.eclipse.jgit.diff.RawTextComparator
import java.nio.charset.StandardCharsets.UTF_8

enum class DiffKind { CONTEXT, REMOVED, ADDED }

data class DiffLine(val kind: DiffKind, val text: String)

// JGit's histogram diff without a repository, capped so a huge task cannot
// blow up the conflict screen.
object TextDiff {
    private const val DEFAULT_CONTEXT = 3
    private const val MAX_LINES = 400

    fun unified(
        local: String,
        remote: String,
        context: Int = DEFAULT_CONTEXT,
        maxLines: Int = MAX_LINES,
    ): List<DiffLine> {
        val a = RawText(local.toByteArray(UTF_8))
        val b = RawText(remote.toByteArray(UTF_8))
        val edits = HistogramDiff().diff(RawTextComparator.DEFAULT, a, b)
        if (edits.isEmpty()) return emptyList()

        val result = ArrayList<DiffLine>()
        var aPos = 0
        var bPos = 0

        fun add(kind: DiffKind, text: String) {
            if (result.size < maxLines) result.add(DiffLine(kind, text))
        }

        for (edit in edits) {
            val from = maxOf(aPos, edit.beginA - context)
            if (from > aPos) {
                bPos += from - aPos
                aPos = from
            }
            while (aPos < edit.beginA) {
                add(DiffKind.CONTEXT, a.getString(aPos))
                aPos++
                bPos++
            }
            while (aPos < edit.endA) {
                add(DiffKind.REMOVED, a.getString(aPos))
                aPos++
            }
            while (bPos < edit.endB) {
                add(DiffKind.ADDED, b.getString(bPos))
                bPos++
            }
            aPos = edit.endA
            bPos = edit.endB
        }

        val tailEnd = minOf(a.size(), aPos + context)
        while (aPos < tailEnd) {
            add(DiffKind.CONTEXT, a.getString(aPos))
            aPos++
            bPos++
        }
        return result
    }
}
