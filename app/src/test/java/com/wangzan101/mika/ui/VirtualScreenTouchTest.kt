package com.wangzan101.mika.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class VirtualScreenTouchTest {
    @Test
    fun normalizeTouchCoordClampsEdgesAndHandlesDegenerateInputs() {
        assertEquals(0f, normalizeTouchCoord(0f, 1080), 0.0001f)
        assertEquals(0.5f, normalizeTouchCoord(540f, 1080), 0.0001f)
        assertEquals(1f, normalizeTouchCoord(1080f, 1080), 0.0001f)

        // Out-of-bounds drag coordinates clamp cleanly into [0, 1]
        assertEquals(0f, normalizeTouchCoord(-42f, 1080), 0.0001f)
        assertEquals(1f, normalizeTouchCoord(1500f, 1080), 0.0001f)

        // Degenerate extents & non-finite floats never produce NaN
        assertEquals(0f, normalizeTouchCoord(100f, 0), 0.0001f)
        assertEquals(0f, normalizeTouchCoord(Float.NaN, 1080), 0.0001f)
        assertEquals(0f, normalizeTouchCoord(Float.POSITIVE_INFINITY, 1080), 0.0001f)
    }

    @Test
    fun scaleTouchToDisplayMapsFullNormalizedRangeWithinPixelBounds() {
        assertEquals(0f, scaleTouchToDisplay(0f, 1080), 0.0001f)
        assertEquals(1079f, scaleTouchToDisplay(1f, 1080), 0.0001f)
        assertEquals(539.5f, scaleTouchToDisplay(0.5f, 1080), 0.01f)
        assertEquals(2399f, scaleTouchToDisplay(1f, 2400), 0.0001f)

        // Clamps out-of-range and degenerate values safely
        assertEquals(0f, scaleTouchToDisplay(-0.2f, 1080), 0.0001f)
        assertEquals(1079f, scaleTouchToDisplay(1.4f, 1080), 0.0001f)
        assertEquals(0f, scaleTouchToDisplay(0.5f, 1), 0.0001f)
        assertEquals(0f, scaleTouchToDisplay(Float.NaN, 1080), 0.0001f)
    }
}
