package com.tulipskun.droidime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsLayoutTest {
    @Test
    fun narrowScreensUseSmallerSideMargins() {
        assertEquals(12, SettingsLayout.horizontalPaddingDp(319f))
        assertEquals(20, SettingsLayout.horizontalPaddingDp(360f))
        assertEquals(20, SettingsLayout.horizontalPaddingDp(1200f))
    }

    @Test
    fun columnFitsNarrowScreenWithoutStackedPadding() {
        val width = 320
        val column = SettingsLayout.contentWidthPx(width, 1f)
        val padding = SettingsLayout.horizontalPaddingDp(width.toFloat())
        assertEquals(width - padding * 2, column)
        assertTrue(column + padding * 2 <= width)
    }

    @Test
    fun columnIsCappedOnWideScreens() {
        assertEquals(SettingsLayout.MAX_WIDTH_DP, SettingsLayout.contentWidthPx(1600, 1f))
        assertTrue(SettingsLayout.contentWidthPx(1600, 2f) <= SettingsLayout.MAX_WIDTH_DP * 2)
    }
}
