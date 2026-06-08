package com.rusure.app

import com.rusure.app.ui.util.formatDuration
import com.rusure.app.ui.util.formatTimestamp
import org.junit.Assert.assertEquals
import org.junit.Test

class FormattersTest {

    @Test
    fun formatDuration_zero() {
        assertEquals("0s", formatDuration(0L))
    }

    @Test
    fun formatDuration_secondsOnly() {
        assertEquals("45s", formatDuration(45_000L))
    }

    @Test
    fun formatDuration_minutesAndSeconds() {
        assertEquals("1m 5s", formatDuration(65_000L))
    }

    @Test
    fun formatDuration_hoursMinutesSeconds() {
        assertEquals("1h 1m 1s", formatDuration(3_661_000L))
    }

    @Test
    fun formatTimestamp_nullIsDash() {
        assertEquals("—", formatTimestamp(null))
    }
}
