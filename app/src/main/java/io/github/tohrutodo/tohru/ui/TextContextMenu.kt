package io.github.tohrutodo.tohru.ui

import androidx.compose.ui.res.stringResource
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuComponent
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuItem
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuKeys
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuSession
import androidx.compose.foundation.text.contextmenu.provider.LocalTextContextMenuDropdownProvider
import androidx.compose.foundation.text.contextmenu.provider.LocalTextContextMenuToolbarProvider
import androidx.compose.foundation.text.contextmenu.provider.TextContextMenuProvider
import androidx.compose.foundation.text.contextmenu.provider.TextContextMenuDataProvider
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CompletableDeferred
import kotlin.math.roundToInt
import io.github.tohrutodo.tohru.R

// The overlay lives in a Service window, where ActionMode-backed text menus
// never appear. This provider draws the menu in the overlay window instead:
// a compact icon row, plus an overflow for Autofill/Process-text actions.
class AppTextContextMenuState {
    internal var entry by mutableStateOf<Entry?>(null)
        private set

    val provider: TextContextMenuProvider = object : TextContextMenuProvider {
        override suspend fun showTextContextMenu(dataProvider: TextContextMenuDataProvider) {
            val id = Any()
            val close = CompletableDeferred<Unit>()
            entry = Entry(id, dataProvider, close)
            try {
                close.await()
            } finally {
                if (entry?.id == id) entry = null
            }
        }
    }

    fun dismiss() {
        entry?.close?.complete(Unit)
        entry = null
    }

    internal data class Entry(
        val id: Any,
        val dataProvider: TextContextMenuDataProvider,
        val close: CompletableDeferred<Unit>,
    )
}

@Composable
fun AppTextContextMenuHost(
    state: AppTextContextMenuState,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    var anchor by remember { mutableStateOf<LayoutCoordinates?>(null) }
    CompositionLocalProvider(
        LocalTextContextMenuToolbarProvider provides state.provider,
        LocalTextContextMenuDropdownProvider provides state.provider,
    ) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .onGloballyPositioned { anchor = it },
        ) {
            content()
            val entry = state.entry
            val coordinates = anchor
            if (entry != null && coordinates != null) {
                AppTextMenu(entry, coordinates, state::dismiss)
            }
        }
    }
}

@Composable
private fun AppTextMenu(
    entry: AppTextContextMenuState.Entry,
    anchor: LayoutCoordinates,
    dismiss: () -> Unit,
) {
    val data = runCatching { entry.dataProvider.data() }.getOrNull()
    val position = runCatching { entry.dataProvider.position(anchor) }.getOrDefault(Offset.Zero)
    if (data == null) {
        LaunchedEffect(Unit) { dismiss() }
        return
    }

    val menu = remember(data) { splitMenuItems(data.components) }
    val primary = menu.primary
    val extras = menu.extras
    val density = LocalDensity.current
    val widthPx = with(density) { (primary.size * 48 + if (extras.isEmpty()) 8 else 56).dp.toPx() }
    val heightPx = with(density) { 56.dp.toPx() }
    val gapPx = with(density) { 8.dp.toPx() }
    val rootWidth = anchor.size.width.toFloat()
    val x = position.x.coerceIn(0f, (rootWidth - widthPx).coerceAtLeast(0f))
    val y = (position.y - heightPx - gapPx).let { above ->
        if (above < 0f) position.y + gapPx else above
    }

    BackHandler { dismiss() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Transparent)
            .clickable(indication = null, interactionSource = null) { dismiss() },
    )
    var overflowOpen by remember { mutableStateOf(false) }
    val session = remember(entry) {
        object : TextContextMenuSession {
            override fun close() = dismiss()
        }
    }
    Column(
        modifier = Modifier.offset {
            IntOffset(
                x = x.roundToInt(),
                y = y.roundToInt(),
            )
        },
    ) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shadowElevation = 6.dp,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                primary.forEach { item ->
                    MenuIcon(item, session, dismiss)
                }
                if (extras.isNotEmpty()) {
                    IconButton(onClick = { overflowOpen = !overflowOpen }) {
                        Icon(
                            painter = painterResource(R.drawable.ic_more_horiz),
                            contentDescription = stringResource(R.string.ui_more_options),
                        )
                    }
                }
            }
        }
        if (overflowOpen && extras.isNotEmpty()) {
            Surface(
                modifier = Modifier.padding(top = 4.dp),
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shadowElevation = 6.dp,
            ) {
                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                    extras.forEachIndexed { index, item ->
                        if (index > 0) {
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant,
                                modifier = Modifier.padding(horizontal = 12.dp),
                            )
                        }
                        Row(
                            modifier = Modifier
                                .clickable {
                                    item.onClick(session)
                                    dismiss()
                                }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val resources = LocalResources.current
                            val hasIcon = item.leadingIcon != 0 && remember(item.leadingIcon, resources) {
                                runCatching { resources.getDrawable(item.leadingIcon, null) != null }
                                    .getOrDefault(false)
                            }
                            if (hasIcon) {
                                Icon(
                                    painter = painterResource(item.leadingIcon),
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                            Text(text = item.label, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuIcon(
    item: TextContextMenuItem,
    session: TextContextMenuSession,
    dismiss: () -> Unit,
) {
    val icon = PRIMARY_ICONS[item.key]
    if (icon == null) {
        IconButton(onClick = { item.onClick(session); dismiss() }) {
            Icon(
                painter = painterResource(R.drawable.ic_more_horiz),
                contentDescription = item.label,
            )
        }
        return
    }
    IconButton(onClick = { item.onClick(session); dismiss() }) {
        Icon(
            painter = painterResource(icon.first),
            contentDescription = stringResource(icon.second),
        )
    }
}

internal data class AppTextMenuItems(
    val primary: List<TextContextMenuItem>,
    val extras: List<TextContextMenuItem>,
)

internal fun splitMenuItems(components: List<TextContextMenuComponent>): AppTextMenuItems {
    val items = components.filterIsInstance<TextContextMenuItem>()
    return AppTextMenuItems(
        primary = PRIMARY_ORDER.mapNotNull { key -> items.firstOrNull { it.key == key } },
        extras = items.filter { item -> PRIMARY_KEYS.none { it == item.key } },
    )
}

private val PRIMARY_ORDER = listOf(
    TextContextMenuKeys.SelectAllKey,
    TextContextMenuKeys.CutKey,
    TextContextMenuKeys.CopyKey,
    TextContextMenuKeys.PasteKey,
)

private val PRIMARY_KEYS = PRIMARY_ORDER.toSet()

private val PRIMARY_ICONS = mapOf(
    TextContextMenuKeys.SelectAllKey to (R.drawable.ic_select_all to R.string.ui_select_all),
    TextContextMenuKeys.CutKey to (R.drawable.ic_content_cut to R.string.ui_cut),
    TextContextMenuKeys.CopyKey to (R.drawable.ic_copy to R.string.ui_copy),
    TextContextMenuKeys.PasteKey to (R.drawable.ic_content_paste to R.string.ui_paste),
)
