package io.github.tohrutodo.tohru

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import io.github.tohrutodo.tohru.ui.SwipeTrigger

class SwipeTriggerTest {
    private val width = 600f

    @Test fun fullPullTriggersEvenWhenSlow() {
        assertTrue(SwipeTrigger.triggers(travel = width, velocity = 0f, width = width))
        assertTrue(SwipeTrigger.triggers(travel = width + 40f, velocity = 0f, width = width))
    }

    @Test fun fastFlickProjectsToTheEnd() {
        assertTrue(SwipeTrigger.triggers(travel = 200f, velocity = 3000f, width = width))
    }

    @Test fun slowShortDragDoesNotTrigger() {
        assertFalse(SwipeTrigger.triggers(travel = 120f, velocity = 200f, width = width))
    }

    @Test fun oppositeVelocityCancelsTheTravel() {
        assertFalse(SwipeTrigger.triggers(travel = 300f, velocity = -2000f, width = width))
    }

    @Test fun unknownWidthNeverTriggers() {
        assertFalse(SwipeTrigger.triggers(travel = 600f, velocity = 3000f, width = 0f))
    }
}
