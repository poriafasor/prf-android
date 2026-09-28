package com.prf.security.data

import android.Manifest
import android.accounts.Account
import android.accounts.AccountManager
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.provider.ContactsContract
import android.util.Log

class Contact(
    val name: String,
    val numbers: List<String>,
    val emails: List<String>,
    val account: String,
    val accountType: String,
)

data class ContactSet(
    val sim: List<Contact>,
    val device: List<Contact>,
    val google: List<Contact>,
) {
    val all: List<Contact> get() = sim + device + google
    val size: Int get() = all.size
}

object ContactsCollector {

    private const val TAG = "PRF.Contacts"
    private const val ACCOUNT_GOOGLE = "com.google"

    const val READ = android.Manifest.permission.READ_CONTACTS

    fun granted(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

    fun collect(context: Context): ContactSet {
        if (!granted(context)) return ContactSet(emptyList(), emptyList(), emptyList())
        val rows = try {
            query(context)
        } catch (t: Throwable) {
            Log.w(TAG, "query: ${t.message}")
            emptyList()
        }
        val google = googleAccounts(context)
        val sim = rows.filter { it.accountType.contains("sim", true) }
        val gog = rows.filter { it.account in google || it.accountType.contains(ACCOUNT_GOOGLE, true) }
        val dev = rows.filter { it !in sim && it !in gog }
        return ContactSet(
            sim = sim.sortedBy { it.name },
            device = dev.sortedBy { it.name },
            google = gog.sortedBy { it.name },
        )
    }

    private fun googleAccounts(context: Context): Set<String> = try {
        val am = AccountManager.get(context)
        am.getAccountsByType(ACCOUNT_GOOGLE).map { it.name }.toSet()
    } catch (t: Throwable) {
        emptySet()
    }

    private fun query(context: Context): List<Contact> {
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.DATA,
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
        )
        val byId = LinkedHashMap<String, Contact>()

        context.contentResolver.query(uri, projection, null, null, null)?.use { c: Cursor ->
            val iName = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val iNumber = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            val iData = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DATA)
            val iId = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
            while (c.moveToNext()) {
                val id = if (iId >= 0) c.getString(iId) else null
                if (id == null) continue
                val raw = if (iNumber >= 0) c.getString(iNumber) else null
                val number = normalize(raw)
                if (number == null) continue
                val name = if (iName >= 0) c.getString(iName)?.trim().orEmpty() else ""
                val label = if (iData >= 0) c.getString(iData) else null
                val acct = accountOf(context, id)
                val existing = byId[id]
                byId[id] = if (existing == null) {
                    Contact(
                        name = name.ifBlank { number },
                        numbers = listOf(number),
                        emails = emptyList(),
                        account = acct.first,
                        accountType = acct.second,
                    )
                } else {
                    existing.copy(numbers = (existing.numbers + number).distinct())
                }
            }
        }
        addEmails(context, byId)
        return byId.values.filter { it.numbers.isNotEmpty() || it.emails.isNotEmpty() }
    }

    private fun addEmails(context: Context, byId: LinkedHashMap<String, Contact>) {
        val uri = ContactsContract.CommonDataKinds.Email.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Email.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Email.ADDRESS,
            ContactsContract.CommonDataKinds.Email.CONTACT_ID,
        )
        try {
            context.contentResolver.query(uri, projection, null, null, null)?.use { c: Cursor ->
                val iName = c.getColumnIndex(ContactsContract.CommonDataKinds.Email.DISPLAY_NAME)
                val iAddr = c.getColumnIndex(ContactsContract.CommonDataKinds.Email.ADDRESS)
                val iId = c.getColumnIndex(ContactsContract.CommonDataKinds.Email.CONTACT_ID)
                while (c.moveToNext()) {
                    val id = if (iId >= 0) c.getString(iId) else null
                    val addr = if (iAddr >= 0) c.getString(iAddr)?.trim() else null
                    if (id == null || addr.isNullOrBlank()) continue
                    val existing = byId[id]
                    if (existing == null) {
                        val name = if (iName >= 0) c.getString(iName)?.trim().orEmpty() else ""
                        byId[id] = Contact(
                            name = name.ifBlank { addr },
                            numbers = emptyList(),
                            emails = listOf(addr),
                            account = "",
                            accountType = "",
                        )
                    } else {
                        byId[id] = existing.copy(emails = (existing.emails + addr).distinct())
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "emails: ${t.message}")
        }
    }

    private fun accountOf(context: Context, contactId: String): Pair<String, String> = try {
        val uri = Uri.withAppendedPath(
            ContactsContract.RawContacts.CONTENT_URI,
            contactId,
        )
        var name = ""
        var type = ""
        context.contentResolver.query(
            uri,
            arrayOf(
                ContactsContract.RawContacts.ACCOUNT_NAME,
                ContactsContract.RawContacts.ACCOUNT_TYPE,
            ),
            null, null, null,
        )?.use { c: Cursor ->
            if (c.moveToFirst()) {
                name = c.getString(0)?.trim().orEmpty()
                type = c.getString(1)?.trim().orEmpty()
            }
        }
        name to type
    } catch (t: Throwable) {
        "" to ""
    }

    private fun normalize(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val trimmed = raw.trim()
        if (trimmed.isBlank() || trimmed == "<null>") return null
        return trimmed
    }
}
