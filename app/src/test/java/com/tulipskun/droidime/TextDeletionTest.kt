package com.tulipskun.droidime

import org.junit.Assert.assertEquals
import org.junit.Test

class TextDeletionTest {
    @Test
    fun thaiMarksStayWithTheirBaseCharacter() {
        assertEquals(0, TextDeletion.previousClusterStart("ก้"))
        assertEquals(2, TextDeletion.previousClusterStart("ก้ข"))
        assertEquals(0, TextDeletion.previousClusterStart("ก้ข", 2))
    }

    @Test
    fun emojiAndCombiningMarksStayTogether() {
        assertEquals(0, TextDeletion.previousClusterStart("👨‍💻"))
        assertEquals(1, TextDeletion.previousClusterStart("a👨‍💻"))
        assertEquals(0, TextDeletion.previousClusterStart("e\u0301"))
    }

    @Test
    fun swipeDeletesOneWordWithTrailingSeparator() {
        assertEquals(6, TextDeletion.previousWordStart("hello world"))
        assertEquals(6, TextDeletion.previousWordStart("hello world "))
        assertEquals(0, TextDeletion.previousWordStart("hello "))
        assertEquals(0, TextDeletion.previousWordStart("hello"))
    }
}
