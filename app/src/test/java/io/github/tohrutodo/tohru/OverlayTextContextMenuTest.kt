package io.github.tohrutodo.tohru

import androidx.compose.foundation.text.contextmenu.data.TextContextMenuItem
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuKeys
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuSeparator
import org.junit.Assert.assertEquals
import org.junit.Test
import io.github.tohrutodo.tohru.ui.splitMenuItems

class OverlayTextContextMenuTest {
    private fun item(key: Any, label: String) = TextContextMenuItem(
        key = key,
        label = label,
        onClick = {},
    )

    @Test fun primaryActionsKeepFixedOrder() {
        val items = listOf(
            item(TextContextMenuKeys.PasteKey, "Paste"),
            TextContextMenuSeparator,
            item(TextContextMenuKeys.CopyKey, "Copy"),
            item(TextContextMenuKeys.SelectAllKey, "Select all"),
        )
        val menu = splitMenuItems(items)
        assertEquals(
            listOf(
                TextContextMenuKeys.SelectAllKey,
                TextContextMenuKeys.CopyKey,
                TextContextMenuKeys.PasteKey,
            ),
            menu.primary.map { it.key },
        )
        assertEquals(emptyList<Any>(), menu.extras)
    }

    @Test fun systemActionsBecomeExtras() {
        val autofill = item(TextContextMenuKeys.AutofillKey, "Autofill")
        val process = item("process-text-1", "Translate")
        val menu = splitMenuItems(
            listOf(
                item(TextContextMenuKeys.CopyKey, "Copy"),
                autofill,
                process,
            ),
        )
        assertEquals(listOf(TextContextMenuKeys.CopyKey), menu.primary.map { it.key })
        assertEquals(listOf(autofill, process), menu.extras)
    }

    @Test fun separatorsAreNotMenuItems() {
        val menu = splitMenuItems(listOf(TextContextMenuSeparator, TextContextMenuSeparator))
        assertEquals(emptyList<Any>(), menu.primary)
        assertEquals(emptyList<Any>(), menu.extras)
    }
}
