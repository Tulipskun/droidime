package com.tulipskun.droidime

// Dictionary view needed by next-word ranking, kept small so the logic can be tested without an
// Android runtime. The shipped dictionary implements this directly.
internal interface NextWordDictionary {
    val words: Array<String>
    val freqs: IntArray
    val maxFrequency: Int
    fun complete(prefix: String, limit: Int): List<Int>
    fun corrections(word: String): List<Int>
    fun frequentCandidates(limit: Int, exclude: Set<String>, prefix: String): List<Int>
    fun indexOf(word: String): Int
}

// Word before the caret and the partial word being typed. `typed` is empty once a separator has
// been committed, so `previous` becomes the word the prediction follows.
internal data class WordContext(val previous: String?, val typed: String)

internal object NextWordModel {
    fun isEnglishWordChar(c: Char): Boolean = c in 'a'..'z' || c in 'A'..'Z' || c == '\''

    private fun isThaiWordChar(c: Char): Boolean = c in '\u0E01'..'\u0E5B'

    fun isWordChar(c: Char): Boolean = isEnglishWordChar(c) || isThaiWordChar(c) || c.isDigit()

    fun context(before: String): WordContext {
        var wordEnd = before.length
        while (wordEnd > 0 && !isWordChar(before[wordEnd - 1])) wordEnd--
        var wordStart = wordEnd
        while (wordStart > 0 && isWordChar(before[wordStart - 1])) wordStart--
        val endsWithWord = before.isNotEmpty() && isWordChar(before.last())
        val typed = if (endsWithWord) before.substring(wordStart, wordEnd) else ""
        val boundary = if (endsWithWord) wordStart else wordEnd
        var prevEnd = boundary
        while (prevEnd > 0 && !isWordChar(before[prevEnd - 1])) prevEnd--
        var prevStart = prevEnd
        while (prevStart > 0 && isWordChar(before[prevStart - 1])) prevStart--
        val previous = if (prevStart < prevEnd) before.substring(prevStart, prevEnd).lowercase() else null
        return WordContext(previous, typed)
    }

    fun normalize(value: String): String? {
        val word = value.trim().lowercase()
        if (word.length !in 2..30) return null
        return word.takeIf { it.all { c -> c in 'a'..'z' || c == '\'' || c in '\u0E01'..'\u0E5B' } }
    }

    // Most frequent continuations after a committed word, used by the Thai strip where there is
    // no partial Latin token to prefix-match.
    fun learnedNextWords(
        previous: String?,
        transitions: Map<String, LinkedHashMap<String, Int>>,
        limit: Int = 3
    ): List<String> {
        val key = normalize(previous ?: return emptyList()) ?: return emptyList()
        return transitions[key].orEmpty().entries
            .sortedByDescending { it.value }
            .take(limit)
            .map { it.key }
    }

    // Adds one observed continuation and prunes the least frequent entries when the model grows.
    // Returns whether anything changed so the caller only persists real updates.
    fun recordTransition(
        transitions: MutableMap<String, LinkedHashMap<String, Int>>,
        previous: String?,
        next: String,
        perPreviousLimit: Int,
        previousLimit: Int
    ): Boolean {
        val from = normalize(previous ?: return false) ?: return false
        val to = normalize(next) ?: return false
        if (from == to) return false
        val counts = transitions.getOrPut(from) { LinkedHashMap() }
        counts[to] = (counts[to] ?: 0) + 1
        if (counts.size > perPreviousLimit) {
            counts.minByOrNull { it.value }?.key?.let { counts.remove(it) }
        }
        while (transitions.size > previousLimit) {
            transitions.remove(transitions.keys.first())
        }
        return true
    }

    // Single-edit distance: substitution, insertion or deletion of one character.
    fun oneEdit(a: String, b: String): Boolean {
        if (a == b) return false
        if (a.length == b.length) {
            var diff = 0
            for (k in a.indices) if (a[k] != b[k]) diff++
            return diff == 1
        }
        if (kotlin.math.abs(a.length - b.length) > 1) return false
        val long = if (a.length > b.length) a else b
        val short = if (a.length > b.length) b else a
        var i = 0
        var j = 0
        while (i < long.length && j < short.length) {
            if (long[i] == short[j]) { i++; j++ } else i++
            if (i - j > 1) return false
        }
        return true
    }

    fun rankedWords(
        typed: String,
        previous: String?,
        dictionary: NextWordDictionary?,
        transitions: Map<String, LinkedHashMap<String, Int>>,
        userWords: Map<String, Int>,
        editPenalty: Int
    ): List<String> {
        if (typed.length > 20) return emptyList()
        val lower = typed.lowercase()
        val scores = LinkedHashMap<String, Int>()

        fun offer(word: String, score: Int) {
            val key = word.lowercase()
            if (key == lower) return
            val old = scores[key]
            if (old == null || score > old) scores[key] = score
        }

        if (dictionary != null) {
            for (index in dictionary.complete(lower, 8)) {
                val word = dictionary.words[index]
                offer(word, dictionary.freqs[index] - (word.length - lower.length))
            }
            if (lower.length >= 3) {
                for (index in dictionary.corrections(lower)) {
                    offer(dictionary.words[index], dictionary.freqs[index] - editPenalty)
                }
            }
        }

        // One observed continuation outranks any generic unigram frequency. Scaling by the
        // dictionary's maximum keeps the boost proportional to the shipped data.
        if (previous != null) {
            val learned = transitions[previous]
            if (!learned.isNullOrEmpty()) {
                val base = (dictionary?.maxFrequency ?: 1) + 1
                for ((word, count) in learned) {
                    if (lower.isNotEmpty() && !word.startsWith(lower)) continue
                    val index = dictionary?.indexOf(word) ?: -1
                    val freq = if (dictionary != null && index >= 0) dictionary.freqs[index] else 0
                    offer(word, count * base + freq / 10 - (word.length - lower.length))
                }
            }
        }

        val exclude = if (previous == null) emptySet() else setOf(previous)
        if (lower.isEmpty()) {
            // No prefix left after a separator: use the most frequent words and the user's own
            // vocabulary, but never predict the word that was just committed.
            if (dictionary != null) {
                for (index in dictionary.frequentCandidates(8, exclude, "")) {
                    offer(dictionary.words[index], dictionary.freqs[index])
                }
            }
            for ((word, count) in userWords) {
                if (word in exclude) continue
                offer(word, count * 3)
            }
        } else {
            for ((word, count) in userWords) {
                if (word == lower) continue
                if (word.length > lower.length && word.startsWith(lower)) {
                    offer(word, count * 3 - (word.length - lower.length))
                } else if (lower.length >= 3 && oneEdit(word, lower)) {
                    offer(word, count * 3 - editPenalty)
                }
            }
        }
        if (scores.isEmpty()) return emptyList()
        val take = if (typed.length >= 2) 2 else 3
        return scores.entries.sortedByDescending { it.value }
            .take(take).map { it.key }
    }
}
