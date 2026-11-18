package com.tulipskun.droidime

import java.text.BreakIterator
import java.util.Locale

// Backward deletion boundaries that match what a user sees, not raw UTF-16 code units.
internal object TextDeletion {
    private val thaiLocale = Locale.forLanguageTag("th")
    private fun isThaiMark(codePoint: Int): Boolean = when (codePoint) {
        0x0E31, 0x0E33, 0x0E34, 0x0E35, 0x0E36, 0x0E37,
        0x0E38, 0x0E39, 0x0E3A,
        0x0E47, 0x0E48, 0x0E49, 0x0E4A, 0x0E4B,
        0x0E4C, 0x0E4D, 0x0E4E -> true
        else -> false
    }

    // Variation selectors and skin tones belong to the emoji they modify and move with it.
    private fun isEmojiModifier(codePoint: Int): Boolean =
        codePoint == 0xFE0F || codePoint in 0x1F3FB..0x1F3FF

    private fun isDeletableMark(codePoint: Int): Boolean {
        if (isEmojiModifier(codePoint)) return false
        if (isThaiMark(codePoint)) return true
        val type = Character.getType(codePoint)
        return type == Character.NON_SPACING_MARK.toInt() ||
            type == Character.COMBINING_SPACING_MARK.toInt() ||
            type == Character.ENCLOSING_MARK.toInt()
    }

    // Start of the visible unit immediately before `end`. Gboard steps back through Thai
    // vowel and tone marks one at a time ("ก็" becomes "ก" on one backspace), while an emoji
    // joined by ZWJ or carrying a skin tone is still removed as a single character.
    fun previousClusterStart(text: String, end: Int = text.length): Int {
        if (end <= 0) return 0
        val cursor = end.coerceAtMost(text.length)
        if (cursor == 0) return 0
        val last = text.codePointBefore(cursor)
        if (isDeletableMark(last)) return cursor - Character.charCount(last)

        var pos = cursor
        while (pos > 0) {
            val mark = text.codePointBefore(pos)
            if (!isEmojiModifier(mark)) break
            pos -= Character.charCount(mark)
        }
        if (pos == 0) return 0
        val base = text.codePointBefore(pos)
        var start = pos - Character.charCount(base)
        while (start > 0 && text.codePointBefore(start) == 0x200D) {
            val zwjStart = start - 1
            if (zwjStart == 0) break
            val joined = text.codePointBefore(zwjStart)
            start = zwjStart - Character.charCount(joined)
            while (start > 0 && isEmojiModifier(text.codePointBefore(start))) {
                val mark = text.codePointBefore(start)
                start -= Character.charCount(mark)
            }
        }
        return start
    }

    // Gboard's swipe-left from Delete removes one word, including a trailing separator.
    // Thai has no spaces, so whitespace splitting would delete the whole sentence; use ICU's word
    // BreakIterator there (the same API Gboard uses) and keep the fast whitespace rule for Latin.
    fun previousWordStart(text: String, thai: Boolean = false): Int {
        var end = text.length
        while (end > 0 && text[end - 1].isWhitespace()) end--
        if (end == 0) return 0
        if (thai) {
            val iterator = BreakIterator.getWordInstance(thaiLocale)
            iterator.setText(text)
            val boundary = iterator.preceding(end)
            if (boundary != BreakIterator.DONE && boundary < end) return boundary
        }
        while (end > 0 && !text[end - 1].isWhitespace()) end--
        return end
    }
}
