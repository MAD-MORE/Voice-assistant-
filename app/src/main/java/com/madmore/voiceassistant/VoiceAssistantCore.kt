package com.madmore.voiceassistant

import android.content.Context
import android.provider.ContactsContract
import java.text.Normalizer
import kotlin.math.max
import kotlin.math.min

/** Pure data used by the speech command pipeline. */
data class ContactCandidate(
    val name: String,
    val phone: String,
    val score: Double
)

enum class CommandType {
    CALL,
    LIST_CONTACTS,
    STOP,
    HELP,
    UNKNOWN
}

data class VoiceCommand(
    val type: CommandType,
    val target: String? = null,
    val raw: String
)

class CommandParser {
    private val callPrefixes = listOf(
        "call ", "phone ", "ring ", "dial ", "contact ",
        "frɛ ", "fre ", "frɛ me ", "fre me "
    )

    fun parse(input: String): VoiceCommand {
        val raw = input.trim()
        val text = normalize(raw)
        if (text.isBlank()) return VoiceCommand(CommandType.UNKNOWN, raw = raw)

        if (listOf("stop", "stop listening", "cancel", "gyae", "gyae tie").any { text == it || text.startsWith("$it ") }) {
            return VoiceCommand(CommandType.STOP, raw = raw)
        }
        if (text.contains("who can i call") || text.contains("my contacts") || text.contains("show contacts") || text.contains("nkyerɛ me contacts")) {
            return VoiceCommand(CommandType.LIST_CONTACTS, raw = raw)
        }
        if (text == "help" || text.contains("what can you do") || text.contains("dɛn na wotumi yɛ")) {
            return VoiceCommand(CommandType.HELP, raw = raw)
        }

        val prefix = callPrefixes.firstOrNull { text.startsWith(it) }
        if (prefix != null) {
            val target = text.removePrefix(prefix).trim()
            return VoiceCommand(CommandType.CALL, target.ifBlank { null }, raw)
        }
        if (text.startsWith("please call ")) {
            return VoiceCommand(CommandType.CALL, text.removePrefix("please call ").trim(), raw)
        }
        if (text.startsWith("please ") && text.contains("call ")) {
            return VoiceCommand(CommandType.CALL, text.substringAfter("call ").trim(), raw)
        }

        // Natural language: “I want to call Gyamera”.
        val callIndex = text.indexOf(" call ")
        if (callIndex >= 0) {
            val target = text.substring(callIndex + 6).trim()
            if (target.isNotBlank()) return VoiceCommand(CommandType.CALL, target, raw)
        }

        return VoiceCommand(CommandType.UNKNOWN, raw = raw)
    }

    fun normalize(value: String): String {
        val decomposed = Normalizer.normalize(value.lowercase(), Normalizer.Form.NFD)
        return decomposed
            .replace("ɛ", "e")
            .replace("ɔ", "o")
            .replace("ŋ", "n")
            .replace("’", "'")
            .replace(Regex("[^a-z0-9' ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}

class FuzzyMatcher {
    fun similarity(a: String, b: String): Double {
        val x = clean(a)
        val y = clean(b)
        if (x.isEmpty() || y.isEmpty()) return 0.0
        if (x == y) return 1.0
        if (x.contains(y) || y.contains(x)) return 0.9
        val distance = levenshtein(x, y)
        return 1.0 - distance.toDouble() / max(x.length, y.length)
    }

    private fun clean(s: String): String = s.lowercase().replace(Regex("[^a-z0-9]"), "")

    private fun levenshtein(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) {
                cur[j] = min(
                    min(cur[j - 1] + 1, prev[j] + 1),
                    prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
                )
            }
            prev = cur
        }
        return prev[b.length]
    }
}

class ContactRepository(private val context: Context) {
    private val matcher = FuzzyMatcher()

    fun findCandidates(query: String, limit: Int = 5): List<ContactCandidate> {
        val all = readContacts()
        return all.map { contact ->
            val direct = matcher.similarity(query, contact.name)
            val firstName = contact.name.substringBefore(' ')
            val first = matcher.similarity(query, firstName)
            ContactCandidate(contact.name, contact.phone, max(direct, first))
        }
            .sortedByDescending { it.score }
            .take(limit)
    }

    fun allNames(limit: Int = 8): List<String> = readContacts().map { it.name }.distinct().take(limit)

    private fun readContacts(): List<ContactCandidate> {
        val resolver = context.contentResolver
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        val output = mutableListOf<ContactCandidate>()
        resolver.query(
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
        return output
            .groupBy { it.name.lowercase() to it.phone }
            .values
            .map { it.first() }
    }
}

class AliasStore(context: Context) {
    private val prefs = context.getSharedPreferences("voice_aliases", Context.MODE_PRIVATE)
    private val parser = CommandParser()

    fun saveAlias(alias: String, contactName: String) {
        prefs.edit().putString(parser.normalize(alias), contactName).apply()
    }

    fun resolveAlias(alias: String): String? = prefs.getString(parser.normalize(alias), null)

    fun allAliases(): Map<String, String> {
        return prefs.all.mapNotNull { (key, value) -> (value as? String)?.let { key to it } }.toMap()
    }
}
