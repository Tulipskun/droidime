package com.tulipskun.droidime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OneHandedLayoutTest {
    @Test fun offModeMatchesParent() {
        assertNull(KeyboardLayout.widthPx(1080, "off"))
        assertEquals(-1, KeyboardLayout.edge(null))
    }

    @Test fun oneHandedModesUseTheSameNarrowWidth() {
        assertEquals((1080 * 0.78f).toInt(), KeyboardLayout.widthPx(1080, "left"))
        assertEquals(KeyboardLayout.widthPx(1080, "left"), KeyboardLayout.widthPx(1080, "right"))
        assertEquals(0, KeyboardLayout.edge("left"))
        assertEquals(1, KeyboardLayout.edge("right"))
    }

    @Test fun tinyScreensStillGetAUsableWidth() {
        assertEquals(1, KeyboardLayout.widthPx(1, "left")!!)
    }

    @Test fun resizeButtonCyclesOffLeftRight() {
        assertEquals("left", KeyboardLayout.nextMode("off"))
        assertEquals("right", KeyboardLayout.nextMode("left"))
        assertEquals("off", KeyboardLayout.nextMode("right"))
        assertEquals("left", KeyboardLayout.nextMode(null))
    }
}
