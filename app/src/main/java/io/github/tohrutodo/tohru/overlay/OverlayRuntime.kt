package io.github.tohrutodo.tohru.overlay

import kotlinx.coroutines.flow.MutableStateFlow

// Shared by the overlay service and the Quick Settings tile in this process.
object OverlayRuntime {
    val active = MutableStateFlow(false)
}
