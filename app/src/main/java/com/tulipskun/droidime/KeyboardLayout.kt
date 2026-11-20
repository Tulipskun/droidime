package com.tulipskun.droidime

// One-handed mode: the keyboard keeps its height but occupies a fraction of the screen width and
// is pinned to one edge. Pure helpers live here so the geometry can be tested without an IME window.
internal object KeyboardLayout {
    const val ONE_HANDED_WIDTH_FRACTION = 0.78f

    // null means "match parent" (the caller supplies ViewGroup.LayoutParams.MATCH_PARENT).
    fun widthPx(screenWidthPx: Int, mode: String?): Int? = when (mode) {
        "left", "right" -> (screenWidthPx * ONE_HANDED_WIDTH_FRACTION).toInt().coerceAtLeast(1)
        else -> null
    }

    // 0 = pinned left, 1 = pinned right, -1 = centred/full width.
    fun edge(mode: String?): Int = when (mode) {
        "left" -> 0
        "right" -> 1
        else -> -1
    }

    // Cycle used by the Resize button in the long-press tools panel.
    fun nextMode(current: String?): String = when (current) {
        "left" -> "right"
        "right" -> "off"
        else -> "left"
    }
}
