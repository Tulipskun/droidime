package com.tulipskun.droidime

// Backward deletion boundaries that match what a user sees, not raw UTF-16 code units.
internal object TextDeletion {
    private fun isThaiMark(codePoint: Int): Boolean = when (codePoint) {
        0x0E31, 0x0E33, 0x0E34, 0x0E35, 0x0E36, 0x0E37,
        0x0E38, 0x0E39, 0x0E3A,
        0x0E47, 0x0E48, 0x0E49, 0x0E4A, 0x0E4B,
        0x0E4C, 0x0E4D, 0x0E4E -> true
        else -> false
    }

    private fun isCombining(codePoint: Int): Boolean {
        if (isThaiMark(codePoint)) return true
        if (codePoint == 0xFE0F || codePoint in 0x1F3FB..0x1F3FF) return true
        val type = Character.getType(codePoint)
        return type == Character.NON_SPACING_MARK.toInt() ||
            type == Character.COMBINING_SPACING_MARK.toInt() ||
            type == Character.ENCLOSING_MARK.toInt()
    }

    // Start of the visible character immediately before `end`. Thai vowel and tone marks stay
    // with their base consonant, and emoji joined by ZWJ are removed as one character.
    fun previousClusterStart(text: String, end: Int = text.length): Int {
        if (end <= 0) return 0
        var cursor = end.coerceAtMost(text.length)
        while (cursor > 0) {
            val mark = text.codePointBefore(cursor)
            if (!isCombining(mark)) break
            cursor -= Character.charCount(mark)
        }
        if (cursor == 0) return 0
        val base = text.codePointBefore(cursor)
        var start = cursor - Character.charCount(base)
        while (start > 0 && text.codePointBefore(start) == 0x200D) {
            val zwjStart = start - 1
            if (zwjStart == 0) break
            val joined = text.codePointBefore(zwjStart)
            start = zwjStart - Character.charCount(joined)
            while (start > 0 && isCombining(text.codePointBefore(start))) {
                val mark = text.codePointBefore(start)
                start -= Character.charCount(mark)
            }
        }
        return start
    }

    // Gboard's swipe-left from Delete removes one word, including a trailing separator.
    fun previousWordStart(text: String): Int {
        var end = text.length
        while (end > 0 && text[end - 1].isWhitespace()) end--
        while (end > 0 && !text[end - 1].isWhitespace()) end--
        return end
    }
}
