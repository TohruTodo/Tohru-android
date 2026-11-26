package io.github.tohrutodo.tohru

import org.junit.Assert.assertEquals
import org.junit.Test
import io.github.tohrutodo.tohru.ui.ThemeMode

class ThemeModeTest {
    @Test fun missingValueFallsBackToSystem() {
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromValue(null))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromValue(""))
    }

    @Test fun storedValueIsCaseInsensitive() {
        assertEquals(ThemeMode.BLACK, ThemeMode.fromValue("black"))
        assertEquals(ThemeMode.LIGHT, ThemeMode.fromValue("LIGHT"))
    }

    @Test fun unknownValueFallsBackToSystem() {
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromValue("sepia"))
    }

    @Test fun darkFlagQueriesSystemOnlyForSystemMode() {
        assertEquals(true, ThemeMode.SYSTEM.isDark(systemDark = true))
        assertEquals(false, ThemeMode.SYSTEM.isDark(systemDark = false))
        assertEquals(false, ThemeMode.LIGHT.isDark(systemDark = true))
        assertEquals(true, ThemeMode.DARK.isDark(systemDark = false))
        assertEquals(true, ThemeMode.BLACK.isDark(systemDark = false))
    }
}
