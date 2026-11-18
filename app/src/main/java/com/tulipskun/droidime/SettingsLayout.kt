package com.tulipskun.droidime

// The settings screen is a single scrolling column. Keep it readable on narrow phones and
// stop the cards from stretching edge to edge on tablets: small side margins on small screens,
// a centred maximum width everywhere else.
internal object SettingsLayout {
    const val MAX_WIDTH_DP = 600

    private const val NARROW_WIDTH_DP = 360f
    private const val NARROW_PADDING_DP = 12
    private const val WIDE_PADDING_DP = 20

    fun horizontalPaddingDp(widthDp: Float): Int =
        if (widthDp < NARROW_WIDTH_DP) NARROW_PADDING_DP else WIDE_PADDING_DP

    // The column's width is the viewport minus its own side margins, capped at MAX_WIDTH_DP.
    fun contentWidthPx(widthPx: Int, density: Float): Int {
        if (widthPx <= 0 || density <= 0f) return 1
        val paddingPx = Math.round(horizontalPaddingDp(widthPx / density) * density)
        val maxPx = Math.round(MAX_WIDTH_DP * density)
        return (widthPx - paddingPx * 2).coerceAtMost(maxPx).coerceAtLeast(1)
    }
}
