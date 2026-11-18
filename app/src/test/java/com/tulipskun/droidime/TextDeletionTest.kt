package com.tulipskun.droidime

import org.junit.Assert.assertEquals
import org.junit.Test

class TextDeletionTest {
    @Test
    fun thaiMarksDeleteBeforeTheirBaseCharacter() {
        assertEquals(1, TextDeletion.previousClusterStart("ก้"))
        assertEquals(2, TextDeletion.previousClusterStart("ก้ข"))
        assertEquals(1, TextDeletion.previousClusterStart("ก้ข", 2))
        assertEquals(1, TextDeletion.previousClusterStart("ก็"))
        assertEquals(0, TextDeletion.previousClusterStart("ก็", 1))
    }

    @Test
    fun emojiAndCombiningMarksStayTogether() {
        assertEquals(0, TextDeletion.previousClusterStart("👨‍💻"))
        assertEquals(1, TextDeletion.previousClusterStart("a👨‍💻"))
        assertEquals(1, TextDeletion.previousClusterStart("e\u0301"))
    }

    @Test
    fun swipeDeletesOneWordWithTrailingSeparator() {
        assertEquals(6, TextDeletion.previousWordStart("hello world"))
        assertEquals(6, TextDeletion.previousWordStart("hello world "))
        assertEquals(0, TextDeletion.previousWordStart("hello "))
        assertEquals(0, TextDeletion.previousWordStart("hello"))
    }

    @Test
    fun thaiWordDeletionUsesIcuBoundaryNotWholeSentence() {
        // Thai has no spaces: a swipe must delete one word, not the entire sentence.
        val before = "ฉันกินข้าว"
        val start = TextDeletion.previousWordStart(before, thai = true)
        org.junit.Assert.assertTrue(
            "expected a word boundary before the end, got $start",
            start > 0 && start < before.length
        )
        assertEquals(6, TextDeletion.previousWordStart("hello world", thai = false))
    }
}
