package dev.localassistant.tools

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import java.text.Normalizer

data class Contact(val name: String, val number: String)

/** Picks the contacts a spoken name refers to: "mamá", "Ana García", "el fontanero"… */
object ContactMatcher {
    private val FILLER = setOf("a", "al", "el", "la", "mi", "my", "to", "the", "de")
    private const val EXACT = 0
    private const val PREFIX = 3

    /** Contacts ranked by how well they match [query]; only real matches are returned. */
    fun match(query: String, contacts: List<Contact>): List<Contact> {
        val wanted = words(query).filter { it !in FILLER }
        if (wanted.isEmpty()) return emptyList()
        val scored = contacts.mapNotNull { contact ->
            score(wanted, words(contact.name))?.let { contact to it }
        }
        val best = scored.minOfOrNull { it.second } ?: return emptyList()
        return scored.filter { it.second == best }.map { it.first }.distinctBy { it.number.filter(Char::isDigit) }
    }

    /** Lower is better; null when a wanted word matches nothing in the name. */
    private fun score(wanted: List<String>, name: List<String>): Int? {
        if (name.isEmpty()) return null
        if (wanted == name) return 0
        // A whole word counts far more than a prefix: "Ana" is Ana García before it is Anabel.
        var total = 0
        for (word in wanted) {
            total += when {
                name.any { it == word } -> EXACT
                name.any { it.startsWith(word) || word.startsWith(it) } -> PREFIX
                else -> return null
            }
        }
        // Extra words in the contact's name make it a slightly worse match: "Ana" prefers
        // "Ana" over "Ana María López".
        return total + (name.size - wanted.size).coerceAtLeast(0)
    }

    fun words(text: String): List<String> =
        Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.isNotEmpty() }

    /** True for "+34 600 11 22 33", "600112233"… as opposed to a name. */
    fun looksLikeNumber(text: String): Boolean {
        val digits = text.count(Char::isDigit)
        return digits >= 3 && text.all { it.isDigit() || it in "+ -().#*" }
    }
}

/** Read-only access to the phone's contacts; everything stays on the device. */
class ContactsDirectory(private val context: Context) {
    fun hasPermission() =
        context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    fun find(name: String): List<Contact> {
        if (!hasPermission()) return emptyList()
        return ContactMatcher.match(name, all())
    }

    private fun all(): List<Contact> {
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
        )
        return context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            null,
            null,
            null,
        )?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val name = cursor.getString(0) ?: continue
                    val number = cursor.getString(1) ?: continue
                    add(Contact(name, number))
                }
            }
        }.orEmpty()
    }
}
