package com.example.callsync

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CallLog
import android.provider.ContactsContract
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class ReadResult(val logs: JSONArray, val newestDate: Long)

object CallReader {

    private fun has(ctx: Context, p: String) =
        ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun typeName(t: Int): String? = when (t) {
        CallLog.Calls.INCOMING_TYPE -> "incoming"
        CallLog.Calls.OUTGOING_TYPE -> "outgoing"
        CallLog.Calls.MISSED_TYPE -> "missed"
        CallLog.Calls.REJECTED_TYPE -> "rejected"
        else -> null // voicemail / blocked / others are ignored
    }

    /** Name saved in the phone's contacts (needs READ_CONTACTS). Cached per read. */
    private fun contactName(ctx: Context, number: String, cache: MutableMap<String, String?>): String? {
        if (cache.containsKey(number)) return cache[number]
        var name: String? = null
        try {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
            ctx.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) name = it.getString(0)?.takeIf { n -> n.isNotBlank() }
            }
        } catch (e: Exception) { }
        cache[number] = name
        return name
    }

    /**
     * Calls of the chosen SIM(s) newer than sinceMs, oldest first.
     * Each row has everything the server needs plus a few UI-only fields (date_ms, end_ms, rec).
     */
    fun read(ctx: Context, sinceMs: Long): ReadResult {
        val out = JSONArray()
        var newest = sinceMs
        if (!has(ctx, Manifest.permission.READ_CALL_LOG)) return ReadResult(out, newest)

        val prefs = Prefs(ctx)
        val mode = prefs.simMode
        val sims = SimInfo.list(ctx)
        val sessions = CallSessions.all(ctx)
        val notes = prefs.notes()
        val recDone = prefs.recDone().toHashSet()
        val canContacts = has(ctx, Manifest.permission.READ_CONTACTS)
        val nameCache = HashMap<String, String?>()
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

        val cols = arrayOf(
            CallLog.Calls._ID, CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME,
            CallLog.Calls.TYPE, CallLog.Calls.DURATION, CallLog.Calls.DATE,
            CallLog.Calls.PHONE_ACCOUNT_ID, CallLog.Calls.LAST_MODIFIED
        )
        ctx.contentResolver.query(
            CallLog.Calls.CONTENT_URI, cols,
            "${CallLog.Calls.DATE} > ?", arrayOf(sinceMs.toString()),
            "${CallLog.Calls.DATE} ASC"
        )?.use { c ->
            val iId = c.getColumnIndexOrThrow(CallLog.Calls._ID)
            val iNum = c.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
            val iName = c.getColumnIndexOrThrow(CallLog.Calls.CACHED_NAME)
            val iType = c.getColumnIndexOrThrow(CallLog.Calls.TYPE)
            val iDur = c.getColumnIndexOrThrow(CallLog.Calls.DURATION)
            val iDate = c.getColumnIndexOrThrow(CallLog.Calls.DATE)
            val iAcc = c.getColumnIndexOrThrow(CallLog.Calls.PHONE_ACCOUNT_ID)
            val iMod = c.getColumnIndex(CallLog.Calls.LAST_MODIFIED)

            while (c.moveToNext()) {
                val date = c.getLong(iDate)
                if (date > newest) newest = date

                val type = typeName(c.getInt(iType)) ?: continue

                // SIM filter. Unknown SIM is kept rather than silently dropped.
                val slot = SimInfo.slotOf(c.getString(iAcc), sims)
                if (mode != 2 && slot != null && slot != mode) continue

                val number = c.getString(iNum)?.takeIf { it.isNotBlank() } ?: "unknown"
                val dur = c.getInt(iDur).coerceAtLeast(0)

                // contact name: call-log cache first, then the phone's contacts
                var name = c.getString(iName)?.takeIf { it.isNotBlank() }
                if (name == null && canContacts && number != "unknown") name = contactName(ctx, number, nameCache)
                val saved: Boolean? = when {
                    name != null -> true
                    canContacts -> false
                    else -> null
                }

                // ringing time + end time
                var ring: Int? = null
                var endMs: Long? = null
                val s = CallSessions.match(sessions, date)
                if (s != null) {
                    ring = if (s.incoming) (((if (s.answer > 0) s.answer else s.end) - s.start) / 1000).toInt()
                    else ((s.end - s.start) / 1000 - dur).toInt()
                    endMs = s.end
                } else if (iMod >= 0) {
                    // fallback: the row is written when the call ends
                    val mod = c.getLong(iMod)
                    val r = (mod - date) / 1000 - dur
                    if (mod > date && r in 0L..180L) { ring = r.toInt(); endMs = mod }
                }
                ring = ring?.coerceIn(0, 3600)

                val id = "${date}_${c.getLong(iId)}"
                out.put(JSONObject().apply {
                    put("device_call_id", id)
                    put("contact_number", number.take(32))
                    put("contact_name", name?.take(128) ?: JSONObject.NULL)
                    put("contact_saved", saved ?: JSONObject.NULL)
                    put("call_type", type)
                    put("duration_sec", dur)
                    put("ring_sec", ring ?: JSONObject.NULL)
                    put("called_at", fmt.format(Date(date)))
                    put("ended_at", endMs?.let { fmt.format(Date(it)) } ?: JSONObject.NULL)
                    put("sim_slot", slot ?: JSONObject.NULL)
                    put("sim_name", slot?.let { sims.getOrNull(it)?.name } ?: JSONObject.NULL)
                    val note = notes.optString(id, "")
                    if (note.isNotBlank()) put("note", note)
                    // UI only
                    put("date_ms", date)
                    put("end_ms", endMs ?: (date + (ring ?: 0) * 1000L + dur * 1000L))
                    put("rec", "u:$id" in recDone)
                })
            }
        }
        return ReadResult(out, newest)
    }
}
