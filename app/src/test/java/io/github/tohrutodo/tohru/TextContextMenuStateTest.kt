package io.github.tohrutodo.tohru

import androidx.compose.foundation.text.contextmenu.data.TextContextMenuData
import androidx.compose.foundation.text.contextmenu.provider.TextContextMenuDataProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import io.github.tohrutodo.tohru.ui.AppTextContextMenuState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextContextMenuStateTest {
    private val provider = object : TextContextMenuDataProvider {
        override fun position(anchor: LayoutCoordinates): Offset = Offset.Zero
        override fun contentBounds(anchor: LayoutCoordinates): Rect = Rect.Zero
        override fun data(): TextContextMenuData = TextContextMenuData(emptyList())
    }

    @Test fun dismissEndsTheActiveSession() = runBlocking {
        val state = AppTextContextMenuState()
        val finished = CompletableDeferred<Unit>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            state.provider.showTextContextMenu(provider)
            finished.complete(Unit)
        }

        assertFalse(finished.isCompleted)
        state.dismiss()
        job.join()
        assertTrue(finished.isCompleted)
    }

    @Test fun showingAgainEndsThePreviousSession() = runBlocking {
        val state = AppTextContextMenuState()
        val first = CompletableDeferred<Unit>()
        val firstJob = launch(start = CoroutineStart.UNDISPATCHED) {
            state.provider.showTextContextMenu(provider)
            first.complete(Unit)
        }
        assertFalse(first.isCompleted)

        val secondJob = launch(start = CoroutineStart.UNDISPATCHED) {
            state.provider.showTextContextMenu(provider)
        }

        yield()
        assertTrue("previous session must end when a new one replaces it", firstJob.isCompleted)
        state.dismiss()
        firstJob.join()
        secondJob.join()
    }

    @Test fun suppressedRequestsAreIgnoredUntilTheNextGesture() = runBlocking {
        val state = AppTextContextMenuState()
        launch(start = CoroutineStart.UNDISPATCHED) {
            state.provider.showTextContextMenu(provider)
        }
        assertTrue(state.entry != null)

        state.suppressReopen = true
        state.dismiss()
        launch(start = CoroutineStart.UNDISPATCHED) {
            state.provider.showTextContextMenu(provider)
        }
        yield()
        assertTrue("the same gesture must not reopen the menu", state.entry == null)

        state.suppressReopen = false
        launch(start = CoroutineStart.UNDISPATCHED) {
            state.provider.showTextContextMenu(provider)
        }
        assertTrue("a new gesture must show the menu again", state.entry != null)
        state.dismiss()
    }
}
