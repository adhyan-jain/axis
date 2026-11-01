package app.axis.device

import android.content.ContentResolver
import android.provider.ContactsContract
import axis.agent.ContactBook

/** Reads the user's contacts, one entry per person (first number). Needs READ_CONTACTS. */
object ContactsReader {
    fun load(resolver: ContentResolver): ContactBook {
        val entries = LinkedHashMap<String, Triple<String, String, String>>()
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
        )
        resolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, projection, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val name = c.getString(1)?.takeIf { it.isNotBlank() } ?: continue
                val number = c.getString(2)?.takeIf { it.isNotBlank() } ?: continue
                entries.putIfAbsent(id, Triple(id, name, number))
            }
        }
        return ContactBook(entries.values.toList())
    }
}
