package com.madmore.voiceassistant

/**
 * Deterministic, dependency-free wake-word scoring.
 * It works on recognition hypotheses and is deliberately conservative:
 * exact phrases score highest, while common recognition distortions such as
 * "helo" and stretched "helloooo" are accepted without accepting arbitrary
 * speech as a wake command.
 */
class WakeWordDetector {
    data class Match(val matched: Boolean, val confidence: Double, val text: String)

    private val variants = listOf("hello", "helloooo", "helo", "hallo", "hullo")

    fun detect(hypotheses: List<String>): Match {
        var best = Match(false, 0.0, "")
        for (raw in hypotheses) {
            val normalized = normalize(raw)
            if (normalized.isBlank()) continue
            val first = normalized.split(' ').first()
            val score = variants.maxOf { similarity(first, it) }
            val exact = variants.contains(first)
            val accepted = exact || score >= 0.90
            val candidate = Match(accepted, if (accepted) score else 0.0, normalized)
            if (candidate.confidence > best.confidence) best = candidate
        }
        return best
    }

    fun removeWakeWord(text: String): String {
        val normalized = normalize(text)
        val words = normalized.split(' ').filter { it.isNotBlank() }
        if (words.isEmpty()) return ""
        val score = variants.maxOf { similarity(words.first(), it) }
        return if (variants.contains(words.first()) || score >= 0.90) {
            words.drop(1).joinToString(" ")
        } else ""
    }

    private fun normalize(value: String): String = value
        .lowercase()
        .replace(Regex("[^a-z0-9\\s]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun similarity(a: String, b: String): Double {
        if (a == b) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val distance = levenshtein(a, b)
        return 1.0 - distance.toDouble() / maxOf(a.length, b.length)
    }

    private fun levenshtein(a: String, b: String): Int {
        val previous = IntArray(b.length + 1) { it }
        val current = IntArray(b.length + 1)
        for (i in a.indices) {
            current[0] = i + 1
            for (j in b.indices) {
                val cost = if (a[i] == b[j]) 0 else 1
                current[j + 1] = minOf(
                    current[j] + 1,
                    previous[j + 1] + 1,
                    previous[j] + cost
                )
            }
            for (j in current.indices) previous[j] = current[j]
        }
        return previous[b.length]
    }
}
