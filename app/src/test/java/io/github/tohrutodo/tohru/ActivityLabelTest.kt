package io.github.tohrutodo.tohru

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import io.github.tohrutodo.tohru.task.Task
import io.github.tohrutodo.tohru.ui.activityLabel
import io.github.tohrutodo.tohru.ui.relativeTime

class ActivityLabelTest {
    private val now = 1_800_000_000_000L

    private fun task(created: Long, updated: Long) =
        Task(id = "1-0001", title = "x", created = created, updated = updated)

    @Test fun bucketsFollowTheAge() {
        assertEquals("vừa xong", relativeTime(now - 30_000L, now))
        assertEquals("1 phút trước", relativeTime(now - 60_000L, now))
        assertEquals("59 phút trước", relativeTime(now - 59 * 60_000L, now))
        assertEquals("1 giờ trước", relativeTime(now - 60 * 60_000L, now))
        assertEquals("23 giờ trước", relativeTime(now - 23 * 60 * 60_000L, now))
        assertEquals("1 ngày trước", relativeTime(now - 24 * 60 * 60_000L, now))
        assertEquals("6 ngày trước", relativeTime(now - 6 * 24 * 60 * 60_000L, now))
    }

    @Test fun oldStampsFallBackToAbsolute() {
        val week = now - 7 * 24 * 60 * 60_000L
        val text = relativeTime(week, now)
        assertTrue(text, text.contains("/"))
        assertTrue(text, !text.endsWith("trước"))
    }

    @Test fun futureStampsReadAsJustNow() {
        assertEquals("vừa xong", relativeTime(now + 10_000L, now))
    }

    @Test fun freshTaskUsesCreateStamp() {
        assertEquals("Tạo 5 phút trước", activityLabel(task(now - 5 * 60_000L, now - 5 * 60_000L), now))
        assertEquals("Tạo 5 phút trước", activityLabel(task(now - 300_000L, now - 296_000L), now))
    }

    @Test fun editedTaskUsesUpdateStamp() {
        assertEquals("Sửa 2 phút trước", activityLabel(task(now - 300_000L, now - 120_000L), now))
        assertEquals("Sửa 1 giờ trước", activityLabel(task(now - 90 * 60_000L, now - 60 * 60_000L), now))
    }
}
