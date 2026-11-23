package com.cosmos.unreddit.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SwipeActionTouchHelperTest {

    private val itemWidth = 1000
    private val flickVelocityThreshold = 250f

    @Test
    fun `right swipe triggers when distance reaches threshold`() {
        assertTrue(
            SwipeActionTouchHelper.shouldTriggerRightSwipe(
                itemWidth = itemWidth,
                maxRightDx = 150f,
                lastActiveDx = 150f,
                rightVelocityPxPerSec = 0f,
                flickVelocityThresholdPxPerSec = flickVelocityThreshold
            )
        )
    }

    @Test
    fun `right swipe stays latched after slight lift-off pullback`() {
        assertTrue(
            SwipeActionTouchHelper.shouldTriggerRightSwipe(
                itemWidth = itemWidth,
                maxRightDx = 170f,
                lastActiveDx = 80f,
                rightVelocityPxPerSec = 0f,
                flickVelocityThresholdPxPerSec = flickVelocityThreshold
            )
        )
    }

    @Test
    fun `right swipe cancels when dragged back below cancel threshold`() {
        assertFalse(
            SwipeActionTouchHelper.shouldTriggerRightSwipe(
                itemWidth = itemWidth,
                maxRightDx = 200f,
                lastActiveDx = 40f,
                rightVelocityPxPerSec = 0f,
                flickVelocityThresholdPxPerSec = flickVelocityThreshold
            )
        )
    }

    @Test
    fun `fast short right flick triggers save`() {
        assertTrue(
            SwipeActionTouchHelper.shouldTriggerRightSwipe(
                itemWidth = itemWidth,
                maxRightDx = 95f,
                lastActiveDx = 95f,
                rightVelocityPxPerSec = 300f,
                flickVelocityThresholdPxPerSec = flickVelocityThreshold
            )
        )
    }

    @Test
    fun `slow short right drag does not trigger save`() {
        assertFalse(
            SwipeActionTouchHelper.shouldTriggerRightSwipe(
                itemWidth = itemWidth,
                maxRightDx = 95f,
                lastActiveDx = 95f,
                rightVelocityPxPerSec = 50f,
                flickVelocityThresholdPxPerSec = flickVelocityThreshold
            )
        )
    }

    @Test
    fun `left swipe latches once trigger threshold is crossed`() {
        assertEquals(
            SwipeActionTouchHelper.LATCHED_SWIPE_THRESHOLD,
            SwipeActionTouchHelper.getLeftSwipeThreshold(
                itemWidth = itemWidth,
                maxLeftDx = 210f,
                lastActiveDx = -90f
            ),
            0.0001f
        )
    }

    @Test
    fun `left swipe cancels latch when dragged back below cancel threshold`() {
        assertEquals(
            SwipeActionTouchHelper.LEFT_TRIGGER_THRESHOLD,
            SwipeActionTouchHelper.getLeftSwipeThreshold(
                itemWidth = itemWidth,
                maxLeftDx = 250f,
                lastActiveDx = -40f
            ),
            0.0001f
        )
    }

    @Test
    fun `left swipe before trigger threshold keeps normal threshold`() {
        assertEquals(
            SwipeActionTouchHelper.LEFT_TRIGGER_THRESHOLD,
            SwipeActionTouchHelper.getLeftSwipeThreshold(
                itemWidth = itemWidth,
                maxLeftDx = 150f,
                lastActiveDx = -150f
            ),
            0.0001f
        )
    }
}
