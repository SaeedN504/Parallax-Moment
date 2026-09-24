package com.deepfx.clock

import org.junit.Assert.*
import org.junit.Test

class ClockSettingsTest {
    @Test fun anchorsAndMotionAreClamped() {
        val s = ClockSettings(x = -2f, y = 4f, scale = 9f, amplitude = -1f, fps = 120).validated()
        assertEquals(0f, s.x, 0f)
        assertEquals(1f, s.y, 0f)
        assertEquals(0.5f, s.scale, 0f)
        assertEquals(0f, s.amplitude, 0f)
        assertEquals(30, s.fps)
    }
    @Test fun invalidNumbersUseSafeDefaults() {
        val s = ClockSettings(x = Float.NaN, speed = Float.POSITIVE_INFINITY).validated()
        assertEquals(0.5f, s.x, 0f)
        assertEquals(12f, s.speed, 0f)
    }
    @Test fun midnightNoonAnd24HourFormatting() {
        assertEquals("12:05", ClockSettings.timeText(0, 5, false))
        assertEquals("12:00", ClockSettings.timeText(12, 0, false))
        assertEquals("23:09", ClockSettings.timeText(23, 9, true))
    }
    @Test fun defaultsKeepClockAndSubjectAnchored() {
        val s = ClockSettings().validated()
        assertEquals(0.5f, s.x, 0f)
        assertEquals(0.35f, s.y, 0f)
        assertTrue(s.fps in 24..30)
    }
}
