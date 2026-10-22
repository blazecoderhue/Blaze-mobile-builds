package com.blaze.assistant.phone

import android.Manifest
import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract
import android.provider.ContactsContract
import com.blaze.assistant.device.Perms
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Add/edit contacts and move/delete calendar events on the phone itself. Approval is asked by the agent. */
object PimEdit {
    val names = setOf("add_contact", "edit_contact", "move_event", "delete_event")

    private val fmt get() = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

    fun describe(name: String, a: JSONObject): Pair<String, String> = when (name) {
        "add_contact" -> "Add contact?" to listOf(a.optString("name"), a.optString("phone"), a.optString("email")).filter { it.isNotBlank() }.joinToString(" · ")
        "edit_contact" -> "Change contact?" to "${a.optString("name")} → " + listOf(a.optString("new_name"), a.optString("phone"), a.optString("email")).filter { it.isNotBlank() }.joinToString(" · ")
        "move_event" -> "Move event?" to "${a.optString("title")} → ${a.optString("new_start")}"
        else -> "Delete event?" to a.optString("title")
    }

    suspend fun run(ctx: Context, name: String, a: JSONObject): String = try {
        when (name) {
            "add_contact" -> addContact(ctx, a)
            "edit_contact" -> editContact(ctx, a)
            "move_event" -> moveEvent(ctx, a)
            "delete_event" -> deleteEvent(ctx, a)
            else -> "Unknown tool."
        }
    } catch (e: SecurityException) { Perms.denied("that") } catch (e: Exception) { "Error: ${e.message}" }

    // ---------- contacts ----------

    private suspend fun addContact(ctx: Context, a: JSONObject): String {
        if (!Perms.ensure(ctx, Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)) return Perms.denied("contacts")
        val nm = a.optString("name").trim().ifBlank { return "A contact needs a name." }
        val ops = arrayListOf(
            ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null).withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null).build(),
            ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI).withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, nm).build())
        a.optString("phone").takeIf { it.isNotBlank() }?.let {
            ops += ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI).withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, it)
                .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE).build() }
        a.optString("email").takeIf { it.isNotBlank() }?.let {
            ops += ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI).withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.Email.ADDRESS, it)
                .withValue(ContactsContract.CommonDataKinds.Email.TYPE, ContactsContract.CommonDataKinds.Email.TYPE_HOME).build() }
        ctx.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
        return "Saved contact $nm."
    }

    private fun rawIdFor(ctx: Context, name: String): Pair<Long, String>? =
        ctx.contentResolver.query(ContactsContract.RawContacts.CONTENT_URI,
            arrayOf(ContactsContract.RawContacts._ID, ContactsContract.RawContacts.DISPLAY_NAME_PRIMARY),
            "${ContactsContract.RawContacts.DISPLAY_NAME_PRIMARY} LIKE ? AND ${ContactsContract.RawContacts.DELETED}=0", arrayOf("%$name%"), null)?.use {
            if (it.moveToFirst()) it.getLong(0) to (it.getString(1) ?: name) else null
        }

    private fun upsert(ctx: Context, raw: Long, mime: String, column: String, value: String) {
        val where = "${ContactsContract.Data.RAW_CONTACT_ID}=? AND ${ContactsContract.Data.MIMETYPE}=?"
        val args = arrayOf(raw.toString(), mime)
        val n = ctx.contentResolver.update(ContactsContract.Data.CONTENT_URI, ContentValues().apply { put(column, value) }, where, args)
        if (n == 0) ctx.contentResolver.insert(ContactsContract.Data.CONTENT_URI, ContentValues().apply {
            put(ContactsContract.Data.RAW_CONTACT_ID, raw); put(ContactsContract.Data.MIMETYPE, mime); put(column, value)
            if (mime == ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE) put(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
            if (mime == ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE) put(ContactsContract.CommonDataKinds.Email.TYPE, ContactsContract.CommonDataKinds.Email.TYPE_HOME)
        })
    }

    private suspend fun editContact(ctx: Context, a: JSONObject): String {
        if (!Perms.ensure(ctx, Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)) return Perms.denied("contacts")
        val (raw, found) = rawIdFor(ctx, a.optString("name").trim()) ?: return "No contact matching \u201c${a.optString("name")}\u201d."
        val changed = mutableListOf<String>()
        a.optString("new_name").takeIf { it.isNotBlank() }?.let { upsert(ctx, raw, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE, ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, it); changed += "name → $it" }
        a.optString("phone").takeIf { it.isNotBlank() }?.let { upsert(ctx, raw, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE, ContactsContract.CommonDataKinds.Phone.NUMBER, it); changed += "phone → $it" }
        a.optString("email").takeIf { it.isNotBlank() }?.let { upsert(ctx, raw, ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE, ContactsContract.CommonDataKinds.Email.ADDRESS, it); changed += "email → $it" }
        return if (changed.isEmpty()) "Nothing to change for $found." else "Updated $found: ${changed.joinToString(", ")}."
    }

    // ---------- calendar ----------

    private data class Ev(val id: Long, val title: String, val start: Long, val end: Long)

    private fun findEvent(ctx: Context, title: String, day: String): Ev? {
        val from: Long; val to: Long
        if (day.isNotBlank()) {
            val d = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(day.take(10)) ?: return null
            from = d.time; to = d.time + 86_400_000L
        } else { from = System.currentTimeMillis() - 86_400_000L; to = System.currentTimeMillis() + 60L * 86_400_000L }
        val b = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(b, from); ContentUris.appendId(b, to)
        return ctx.contentResolver.query(b.build(), arrayOf(CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END),
            "${CalendarContract.Instances.TITLE} LIKE ?", arrayOf("%$title%"), "${CalendarContract.Instances.BEGIN} ASC")?.use {
            if (it.moveToFirst()) Ev(it.getLong(0), it.getString(1) ?: title, it.getLong(2), it.getLong(3)) else null
        }
    }

    private suspend fun moveEvent(ctx: Context, a: JSONObject): String {
        if (!Perms.ensure(ctx, Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)) return Perms.denied("calendar")
        val ev = findEvent(ctx, a.optString("title"), a.optString("day")) ?: return "No upcoming event matching \u201c${a.optString("title")}\u201d."
        val start = fmt.parse(a.optString("new_start"))?.time ?: return "new_start must be yyyy-MM-dd HH:mm."
        val len = a.optInt("duration_minutes", 0).takeIf { it > 0 }?.times(60_000L) ?: (ev.end - ev.start).coerceAtLeast(15 * 60_000L)
        val n = ctx.contentResolver.update(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, ev.id),
            ContentValues().apply { put(CalendarContract.Events.DTSTART, start); put(CalendarContract.Events.DTEND, start + len) }, null, null)
        val f = SimpleDateFormat("EEE d MMM HH:mm", Locale.getDefault())
        return if (n > 0) "Moved \u201c${ev.title}\u201d to ${f.format(Date(start))}–${SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(start + len))}." else "Couldn't move \u201c${ev.title}\u201d (it may belong to a read-only calendar)."
    }

    private suspend fun deleteEvent(ctx: Context, a: JSONObject): String {
        if (!Perms.ensure(ctx, Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)) return Perms.denied("calendar")
        val ev = findEvent(ctx, a.optString("title"), a.optString("day")) ?: return "No upcoming event matching \u201c${a.optString("title")}\u201d."
        val n = ctx.contentResolver.delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, ev.id), null, null)
        return if (n > 0) "Deleted \u201c${ev.title}\u201d (${SimpleDateFormat("EEE d MMM HH:mm", Locale.getDefault()).format(Date(ev.start))})." else "Couldn't delete \u201c${ev.title}\u201d (read-only calendar?)."
    }

    fun tools(): List<JSONObject> {
        fun fn(n: String, d: String, props: JSONObject, req: List<String>) = JSONObject().put("type", "function").put("function", JSONObject().put("name", n).put("description", d)
            .put("parameters", JSONObject().put("type", "object").put("properties", props).put("required", JSONArray(req))))
        fun s(d: String) = JSONObject().put("type", "string").put("description", d)
        return listOf(
            fn("add_contact", "Save a new contact on the phone. Asks the user first.", JSONObject().put("name", s("Full name")).put("phone", s("Phone number")).put("email", s("Email")), listOf("name")),
            fn("edit_contact", "Change an existing phone contact's name, number or email. Asks the user first.", JSONObject().put("name", s("Current name (or part)")).put("new_name", s("New name"))
                .put("phone", s("New phone number")).put("email", s("New email")), listOf("name")),
            fn("move_event", "Move a phone calendar event to a new time. Asks the user first.", JSONObject().put("title", s("Event title (or part)")).put("day", s("yyyy-MM-dd it's on now, if known"))
                .put("new_start", s("yyyy-MM-dd HH:mm")).put("duration_minutes", JSONObject().put("type", "integer").put("description", "New length; default keeps it")), listOf("title", "new_start")),
            fn("delete_event", "Delete a phone calendar event. Asks the user first.", JSONObject().put("title", s("Event title (or part)")).put("day", s("yyyy-MM-dd, if known")), listOf("title")),
        )
    }
}
