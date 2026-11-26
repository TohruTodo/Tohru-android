package io.github.tohrutodo.tohru

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import io.github.tohrutodo.tohru.task.TaskParser

class TaskParserTest {
    @Test fun plainTitleHasNoPrefix() {
        val parsed = TaskParser.parse("upload training data")
        assertNull(parsed.prefix)
        assertNull(parsed.topic)
        assertEquals("upload training data", parsed.body)
    }

    @Test fun prefixIsExtracted() {
        val parsed = TaskParser.parse("aniyt:add zero entry")
        assertEquals("aniyt", parsed.prefix)
        assertNull(parsed.topic)
        assertEquals("add zero entry", parsed.body)
    }

    @Test fun prefixAndTopicAreExtracted() {
        val parsed = TaskParser.parse("task1(topic1): fix layout")
        assertEquals("task1", parsed.prefix)
        assertEquals("topic1", parsed.topic)
        assertEquals("fix layout", parsed.body)
    }


    @Test fun unicodePrefixAndTopicAreAccepted() {
        val parsed = TaskParser.parse("Nhóm_Công_Việc(Chủ Đề): Việc cần làm")
        assertEquals("nhóm công việc", parsed.prefix)
        assertEquals("chủ đề", parsed.topic)
        assertEquals("Việc cần làm", parsed.body)
    }

    @Test fun unicodeWithoutSpacesIsAccepted() {
        val parsed = TaskParser.parse("仕事(今日): やる")
        assertEquals("仕事", parsed.prefix)
        assertEquals("今日", parsed.topic)
        assertEquals("やる", parsed.body)
    }

    @Test fun colonWithoutSpaceStillParses() {
        val parsed = TaskParser.parse("nhóm(chủ đề):title")
        assertEquals("nhóm", parsed.prefix)
        assertEquals("chủ đề", parsed.topic)
        assertEquals("title", parsed.body)
    }

    @Test fun underscoresInTopicStayLiteral() {
        val parsed = TaskParser.parse("work(chủ_đề): task")
        assertEquals("work", parsed.prefix)
        assertEquals("chủ_đề", parsed.topic)
    }

    @Test fun inboxAliasBecomesUntagged() {
        assertEquals(null, TaskParser.parse("inbox:buy milk").prefix)
        assertEquals(null, TaskParser.parse("inbox(topic):buy milk").topic)
        assertEquals("buy milk", TaskParser.parse("inbox(topic):buy milk").body)
    }

    @Test fun configuredInboxAliasBecomesUntagged() {
        try {
            TaskParser.configureInbox(setOf("inbox", "hộp"))
            assertNull(TaskParser.parse("hộp: việc").prefix)
        } finally {
            TaskParser.configureInbox(setOf("inbox"))
        }
    }

    @Test fun urlsAndTimesAreNotGroups() {
        assertNull(TaskParser.parse("https://example.com").prefix)
        assertNull(TaskParser.parse("http://example.com").prefix)
        assertNull(TaskParser.parse("10:30 họp").prefix)
        assertNull(TaskParser.parse("C:\\Users\\me").prefix)
        assertNull(TaskParser.parse("mailto:user@example.com").prefix)
    }

    @Test fun uppercaseIsAcceptedAndNormalized() {
        val parsed = TaskParser.parse("TODO: fix tests")
        assertEquals("todo", parsed.prefix)
        assertEquals("fix tests", parsed.body)
    }

    @Test fun invalidSpecialCharactersStayPlain() {
        val parsed = TaskParser.parse("work+:fix")
        assertNull(parsed.prefix)
        assertEquals("work+:fix", parsed.body)
    }

    @Test fun inboxGroupsUnprefixed() {
        assertEquals("inbox", TaskParser.groupKey(TaskParser.parse("plain")))
        assertEquals("aniyt", TaskParser.groupKey(TaskParser.parse("aniyt:x")))
    }
}
