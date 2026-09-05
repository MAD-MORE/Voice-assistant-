package com.madmore.voiceassistant

import android.content.Context
import android.provider.ContactsContract
import java.text.Normalizer
import kotlin.math.max
import kotlin.math.min

data class ContactCandidate(val name: String, val phone: String, val score: Double)

enum class CommandType { CALL, LIST_CONTACTS, STOP, HELP, UNKNOWN }

data class VoiceCommand(val type: CommandType, val target: String? = null, val raw: String)

class CommandParser {
    private val callPrefixes = listOf(
        "please call ", "please phone ", "please ring ",
        "frɛ me ", "fre me ", "call ", "phone ", "ring ", "dial ", "contact ",
        "frɛ ", "fre "
    )

    fun parse(input: String): VoiceCommand {
        val raw = input.trim()
        val text = normalize(raw)
        if (text.isBlank()) return VoiceCommand(CommandType.UNKNOWN, raw = raw)
        if (listOf("stop", "stop listening", "cancel", "gyae", "gyae tie", "enough").any { text == it || text.startsWith("$it ") }) return VoiceCommand(CommandType.STOP, raw = raw)
        if (text.contains("who can i call") || text.contains("my contacts") || text.contains("show contacts") || text.contains("list contacts") || text.contains("nkyere me contacts")) return VoiceCommand(CommandType.LIST_CONTACTS, raw = raw)
        if (text == "help" || text.contains("what can you do") || text.contains("den na wotumi ye")) return VoiceCommand(CommandType.HELP, raw = raw)
        val prefix = callPrefixes.firstOrNull { text.startsWith(it) }
        if (prefix != null) return VoiceCommand(CommandType.CALL, text.removePrefix(prefix).trim().ifBlank { null }, raw)
        val callIndex = text.indexOf(" call ")
        if (callIndex >= 0) {
            val target = text.substring(callIndex + 6).trim()
            if (target.isNotBlank()) return VoiceCommand(CommandType.CALL, target, raw)
        }
        return VoiceCommand(CommandType.UNKNOWN, raw = raw)
    }

    fun normalize(value: String): String {
        return Normalizer.normalize(value.lowercase(LocaleHolder.locale), Normalizer.Form.NFD)
            .replace("ɛ", "e").replace("ɔ", "o").replace("ŋ", "n")
            .replace("’", "'").replace("-", " ")
            .replace(Regex("[^a-z0-9' ]"), " ")
            .replace(Regex("\\s+"), " ").trim()
    }

    private object LocaleHolder { val locale = java.util.Locale.ROOT }
}

class FuzzyMatcher {
    fun similarity(a: String, b: String): Double {
        val x = key(a); val y = key(b)
        if (x.isEmpty() || y.isEmpty()) return 0.0
        if (x == y) return 1.0
        if (x.startsWith(y) || y.startsWith(x)) return 0.94
        if (x.contains(y) || y.contains(x)) return 0.90
        val edit = 1.0 - levenshtein(x, y).toDouble() / max(x.length, y.length)
        val phonetic = phoneticSimilarity(x, y)
        return max(edit, phonetic * 0.92)
    }

    fun tokenAwareSimilarity(query: String, name: String): Double {
        val q = normalizeTokens(query)
        val n = normalizeTokens(name)
        if (q.isEmpty() || n.isEmpty()) return 0.0
        val whole = similarity(q.joinToString(" "), n.joinToString(" "))
        val bestToken = q.maxOfOrNull { qt -> n.maxOfOrNull { nt -> similarity(qt, nt) } ?: 0.0 } ?: 0.0
        val ordered = if (q.all { token -> n.any { it == token } }) 1.0 else 0.0
        return max(whole, max(bestToken * 0.96, ordered * 0.98))
    }

    private fun normalizeTokens(value: String): List<String> = value.lowercase(LocaleHolder.locale)
        .replace("ɛ", "e").replace("ɔ", "o").replace("ŋ", "n")
        .replace(Regex("[^a-z0-9 ]"), " ")
        .split(Regex("\\s+"))
        .filter { it.isNotBlank() }

    private fun key(s: String): String = s.lowercase(LocaleHolder.locale)
        .replace("ɛ", "e").replace("ɔ", "o").replace("ŋ", "n")
        .replace(Regex("[^a-z0-9]"), "")

    private fun phoneticSimilarity(a: String, b: String): Double {
        val pa = phonetic(a); val pb = phonetic(b)
        if (pa == pb) return 1.0
        if (pa.startsWith(pb) || pb.startsWith(pa)) return 0.92
        return 1.0 - levenshtein(pa, pb).toDouble() / max(pa.length, pb.length)
    }

    // Lightweight Soundex-like key. It is intentionally tolerant of common
    // speech substitutions such as c/k/q, f/ph, s/z and b/p.
    private fun phonetic(value: String): String {
        if (value.isEmpty()) return ""
        val s = value.lowercase(LocaleHolder.locale)
            .replace("ph", "f").replace("gh", "g").replace("qu", "k")
            .replace('c', 'k').replace('q', 'k').replace('z', 's').replace('v', 'f')
            .replace('p', 'b').replace('t', 'd')
        val first = s.first()
        val mapped = buildString {
            append(first)
            var last = code(first)
            for (ch in s.drop(1)) {
                val code = code(ch)
                if (code != '0' && code != last) append(code)
                if (code != '0') last = code
            }
        }
        return mapped.take(8)
    }

    private fun code(c: Char): Char = when (c) {
        in "bfpv" -> '1'
        in "cgjkqsxz" -> '2'
        in "dt" -> '3'
        'l' -> '4'
        in "mn" -> '5'
        'r' -> '6'
        else -> '0'
    }

    private fun levenshtein(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1); cur[0] = i
            for (j in 1..b.length) cur[j] = min(min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            prev = cur
        }
        return prev[b.length]
    }

    private object LocaleHolder { val locale = java.util.Locale.ROOT }
}

class ContactRepository(private val context: Context) {
    private val matcher = FuzzyMatcher()
    @Volatile private var cache: List<ContactCandidate>? = null

    fun refresh() { cache = null }

    fun findCandidates(query: String, limit: Int = 5): List<ContactCandidate> {
        val normalizedQuery = CommandParser().normalize(query)
        if (normalizedQuery.isBlank()) return emptyList()
        return readContacts().map { contact ->
            val direct = matcher.tokenAwareSimilarity(normalizedQuery, contact.name)
            val first = matcher.similarity(normalizedQuery, contact.name.substringBefore(' '))
            val nickname = nicknameScore(normalizedQuery, contact.name)
            contact.copy(score = max(direct, max(first, nickname)))
        }.sortedWith(compareByDescending<ContactCandidate> { it.score }.thenBy { it.name.lowercase() }).take(limit)
    }

    fun allNames(limit: Int = 8): List<String> = readContacts().map { it.name }.distinct().take(limit)

    private fun nicknameScore(query: String, name: String): Double {
        val q = query.replace(" ", "")
        val n = name.lowercase().replace(" ", "")
        if (q.length >= 3 && n.startsWith(q.take(min(4, q.length)))) return 0.93
        return 0.0
    }

    private fun readContacts(): List<ContactCandidate> {
        cache?.let { return it }
        val projection = arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER)
        val output = mutableListOf<ContactCandidate>()
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            null,
            null,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
        )?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numberIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameIndex).orEmpty().trim()
                val number = cursor.getString(numberIndex).orEmpty().trim()
                if (name.isNotBlank() && number.isNotBlank()) output += ContactCandidate(name, number, 0.0)
            }
        }
        val result = output.groupBy { it.name.lowercase() to it.phone }.values.map { it.first() }
        cache = result
        return result
    }
}

class AliasStore(context: Context) {
    private val prefs = context.getSharedPreferences("voice_aliases", Context.MODE_PRIVATE)
    private val parser = CommandParser()

    fun saveAlias(alias: String, contactName: String) {
        val normalized = parser.normalize(alias)
        val editor = prefs.edit().putString(normalized, contactName)
        relationshipSynonyms(normalized).forEach { editor.putString(it, contactName) }
        editor.apply()
    }

    private fun relationshipSynonyms(alias: String): Set<String> = when (alias) {
        "my son", "son" -> setOf("my son", "son", "ba", "me ba")
        "my daughter", "daughter" -> setOf("my daughter", "daughter", "ba", "me ba")
        "my child", "child" -> setOf("my child", "child", "ba", "me ba")
        "my wife", "wife" -> setOf("my wife", "wife", "me yere")
        "my husband", "husband" -> setOf("my husband", "husband", "me kun")
        else -> emptySet()
    }

    fun resolveAlias(alias: String): String? = prefs.getString(parser.normalize(alias), null)
    fun allAliases(): Map<String, String> = prefs.all.mapNotNull { (key, value) -> (value as? String)?.let { key to it } }.toMap()
}
