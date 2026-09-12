package com.muddassir.clearview.media.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The playback-speed surface shared by the YouTube and Instagram players:
 * preset options must reach at least 5×, the custom slider bounds must be valid
 * and match the step, and [formatRate] must render a clean, locale-independent
 * label (no floating-point noise, no locale comma).
 */
class PlaybackSpeedTest {

    @Test
    fun `presets reach at least 5x and are strictly ascending`() {
        assertTrue("presets must offer at least 5x", SPEED_OPTIONS.max() >= 5.0)
        assertEquals("1x must be a preset", 1.0, SPEED_OPTIONS.first { it == 1.0 }, 0.0)
        assertTrue("no non-positive preset", SPEED_OPTIONS.all { it > 0.0 })
        assertEquals(
            "presets must be strictly ascending",
            SPEED_OPTIONS.sorted(),
            SPEED_OPTIONS
        )
    }

    @Test
    fun `custom speed bounds are valid and cover the presets`() {
        assertTrue(MIN_CUSTOM_SPEED > 0.0)
        assertTrue("custom range must reach at least 5x", MAX_CUSTOM_SPEED >= 5.0)
        assertTrue(MAX_CUSTOM_SPEED > MIN_CUSTOM_SPEED)
        assertTrue(CUSTOM_SPEED_STEP > 0.0)
        // Every preset must be reachable inside the custom range.
        assertTrue(SPEED_OPTIONS.all { it in MIN_CUSTOM_SPEED..MAX_CUSTOM_SPEED })
        // The range must divide evenly into whole steps (slider `steps` math).
        val steps = (MAX_CUSTOM_SPEED - MIN_CUSTOM_SPEED) / CUSTOM_SPEED_STEP
        assertEquals(steps, Math.round(steps).toDouble(), 1e-9)
    }

    @Test
    fun `formatRate drops the decimal for whole speeds`() {
        assertEquals("1x", formatRate(1.0))
        assertEquals("2x", formatRate(2.0))
        assertEquals("5x", formatRate(5.0))
    }

    @Test
    fun `formatRate keeps up to two decimals and strips trailing zeros`() {
        assertEquals("1.25x", formatRate(1.25))
        assertEquals("1.5x", formatRate(1.5))
        assertEquals("1.75x", formatRate(1.75))
        assertEquals("0.25x", formatRate(0.25))
        assertEquals("2.5x", formatRate(2.5))
    }

    @Test
    fun `formatRate rounds away floating point noise`() {
        // A slider value like 1.3500000000000001 must render as "1.35x", never
        // the raw double's toString().
        assertEquals("1.35x", formatRate(1.3500000000000001))
        assertEquals("3.05x", formatRate(3.0500000000000003))
        // Locale independence: a dot, never a comma.
        assertTrue(formatRate(1.35).contains("."))
        assertTrue(!formatRate(1.35).contains(","))
    }
}
