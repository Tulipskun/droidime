package com.tulipskun.droidime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NextWordModelTest {
    private class FakeDictionary : NextWordDictionary {
        override val words = arrayOf("and", "cat", "dog", "hello", "the", "world")
        override val freqs = intArrayOf(90, 80, 70, 60, 100, 50)
        override val maxFrequency = 100

        override fun complete(prefix: String, limit: Int): List<Int> {
            if (prefix.isEmpty()) return emptyList()
            return words.indices
                .filter { words[it].startsWith(prefix) && words[it].length > prefix.length }
                .sortedByDescending { freqs[it] }.take(limit)
        }

        override fun corrections(word: String): List<Int> = emptyList()

        override fun frequentCandidates(limit: Int, exclude: Set<String>, prefix: String): List<Int> =
            words.indices
                .filter { words[it] !in exclude && words[it].startsWith(prefix) }
                .sortedByDescending { freqs[it] }.take(limit)

        override fun indexOf(word: String): Int = words.indexOf(word)
    }

    @Test fun contextReadsPreviousWordAfterSpace() {
        val context = NextWordModel.context("hello the ")
        assertEquals("the", context.previous)
        assertEquals("", context.typed)
    }

    @Test fun learnedTransitionOutranksFrequentFallback() {
        val transitions = HashMap<String, LinkedHashMap<String, Int>>()
        assertTrue(NextWordModel.recordTransition(transitions, "hello", "world", 16, 2000))
        assertTrue(NextWordModel.recordTransition(transitions, "hello", "world", 16, 2000))
        val ranked = NextWordModel.rankedWords("", "hello", FakeDictionary(), transitions, emptyMap(), 5)
        assertEquals("world", ranked.first())
        assertFalse("hello" in ranked)
    }

    @Test fun contextReadsThaiWordAfterSpace() {
        val context = NextWordModel.context("สวัสดี ")
        assertEquals("สวัสดี", context.previous)
        assertEquals("", context.typed)
    }

    @Test fun thaiWordsCanBeLearnedAsTransitions() {
        val transitions = HashMap<String, LinkedHashMap<String, Int>>()
        assertTrue(NextWordModel.recordTransition(transitions, "สวัสดี", "ขอบคุณ", 16, 2000))
        assertEquals(listOf("ขอบคุณ"), NextWordModel.learnedNextWords("สวัสดี", transitions))
    }

    @Test fun frequentFallbackExcludesPreviousWord() {
        val ranked = NextWordModel.rankedWords(
            "", "hello", FakeDictionary(), emptyMap(), mapOf("cat" to 9), 5
        )
        assertTrue("the" in ranked)
        assertFalse("hello" in ranked)
    }
}
