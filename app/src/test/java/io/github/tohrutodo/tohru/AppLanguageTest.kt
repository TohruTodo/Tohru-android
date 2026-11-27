package io.github.tohrutodo.tohru

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLanguageTest {
    @Test fun storedValuesRoundTrip() {
        AppLanguage.entries.forEach { language ->
            assertEquals(language, AppLanguage.fromValue(language.value))
        }
    }

    @Test fun unknownOrMissingValueFallsBackToSystem() {
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromValue(null))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromValue(""))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromValue("fr"))
    }

    @Test fun onlyExplicitLanguagesCarryATag() {
        assertEquals(null, AppLanguage.SYSTEM.tag)
        assertEquals("vi", AppLanguage.VIETNAMESE.tag)
        assertEquals("en", AppLanguage.ENGLISH.tag)
    }

    @Test fun storedLanguageOverridesOnlyWhenItDiffers() {
        assertFalse(AppLocale.needsOverride("", AppLanguage.SYSTEM))
        assertTrue(AppLocale.needsOverride("en", AppLanguage.SYSTEM))
        assertFalse(AppLocale.needsOverride("vi", AppLanguage.VIETNAMESE))
        assertTrue(AppLocale.needsOverride("", AppLanguage.ENGLISH))
        assertTrue(AppLocale.needsOverride("vi", AppLanguage.ENGLISH))
        assertFalse(AppLocale.needsOverride("en", AppLanguage.ENGLISH))
    }
}
