package com.example.callsync

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import org.json.JSONArray
import org.json.JSONObject

data class SyncResult(val ok: Boolean, val message: String)

/** One place that uploads new calls, notes, phone status and recordings. Safe to call from anywhere. */
object SyncEngine {
    private val lock = Any()

    fun run(ctx: Context): SyncResult = synchronized(lock) {
        val prefs = Prefs(ctx)
        val token = prefs.token
        val base = prefs.baseUrl
        if (token == null || base == null) {
            return@synchronized SyncResult(false, "Not registered")
        }
        if (ctx.checkSelfPermission(Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) {
            prefs.status = "Call log permission is missing"
            // still report battery / network so the company can see the phone is alive
            try { Api.post("$base/sync.php", JSONObject().put("logs", JSONArray()).put("device", DeviceInfo.json(ctx)), token) } catch (e: Exception) { }
            return@synchronized SyncResult(false, prefs.status)
        }
        try {
            val res = CallReader.read(ctx, prefs.lastSync)
            val total = res.logs.length()
            val device = DeviceInfo.json(ctx)
            var i = 0
            do {
                val batch = JSONArray()
                for (j in i until minOf(i + 500, total)) batch.put(res.logs.get(j))
                val reply = Api.post("$base/sync.php", JSONObject().put("logs", batch).put("device", device), token)
                reply.optString("company_name").takeIf { it.isNotBlank() }?.let { prefs.companyName = it }
                if (reply.has("record_calls")) prefs.recAllowed = reply.optBoolean("record_calls", true)
                i += 500
            } while (i < total)
            // advance only after every batch succeeded
            prefs.lastSync = res.newestDate
            prefs.lastSyncOk = System.currentTimeMillis()

            pushNotesLocked(ctx, base, token)

            var msg = if (total == 0) "Up to date" else "$total new call(s) uploaded"
            try {
                RecordingSync.run(ctx, base, token)?.let { msg += " · $it" }
            } catch (e: Exception) { }
            prefs.status = msg
            SyncResult(true, msg)
        } catch (e: HttpError) {
            prefs.status = when (e.code) {
                401 -> "Disconnected: this ID was registered on another phone. Connect again."
                403 -> "Blocked by your company. Contact your manager."
                else -> "Sync failed (server ${e.code})"
            }
            SyncResult(false, prefs.status)
        } catch (e: Exception) {
            prefs.status = "Sync failed: ${e.message ?: "network error"}"
            SyncResult(false, prefs.status)
        }
    }

    /** Sends notes written on calls that were already uploaded. */
    fun pushNotes(ctx: Context) = synchronized(lock) {
        val p = Prefs(ctx)
        val token = p.token ?: return@synchronized
        val base = p.baseUrl ?: return@synchronized
        try { pushNotesLocked(ctx, base, token) } catch (e: Exception) { }
    }

    private fun pushNotesLocked(ctx: Context, base: String, token: String) {
        val p = Prefs(ctx)
        val ids = p.pendingNotes()
        if (ids.isEmpty()) return
        val notes = p.notes()
        val arr = JSONArray()
        ids.take(200).forEach { arr.put(JSONObject().put("device_call_id", it).put("note", notes.optString(it, ""))) }
        Api.post("$base/note.php", JSONObject().put("notes", arr), token)
        p.clearPendingNotes(ids.take(200))
    }
}
