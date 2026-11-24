package com.ryanmorrisroe.lyriamp.feature.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NowPlayingDismissTest {
    @Test
    fun downSwipeDismissesAtThresholdOnlyWhenStartedAtTop() {
        assertTrue(
            shouldDismissNowPlaying(
                cumulativeDragPx = 48f,
                dismissalThresholdPx = 48f,
                startedAtTop = true
            )
        )
        assertTrue(
            shouldDismissNowPlaying(
                cumulativeDragPx = 49f,
                dismissalThresholdPx = 48f,
                startedAtTop = true
            )
        )
    }

    @Test
    fun swipeDoesNotDismissBelowThresholdOrBelowTop() {
        assertFalse(
            shouldDismissNowPlaying(
                cumulativeDragPx = 47f,
                dismissalThresholdPx = 48f,
                startedAtTop = true
            )
        )
        assertFalse(
            shouldDismissNowPlaying(
                cumulativeDragPx = 49f,
                dismissalThresholdPx = 48f,
                startedAtTop = false
            )
        )
        assertFalse(
            shouldDismissNowPlaying(
                cumulativeDragPx = -49f,
                dismissalThresholdPx = 48f,
                startedAtTop = true
            )
        )
    }
}
