package com.example.callsync

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("callsync", Context.MODE_PRIVATE)

    private fun str(k: String) = sp.getString(k, null)
    private fun put(k: String, v: String?) { sp.edit().putString(k, v).apply() }

    var baseUrl: String?
        get() = str("base_url")
        set(v) = put("base_url", v)

    var token: String?
        get() = str("token")
        set(v) = put("token", v)

    var employeeId: String?
        get() = str("employee_id")
        set(v) = put("employee_id", v)

    var employeeName: String?
        get() = str("employee_name")
        set(v) = put("employee_name", v)

    var phone: String?
        get() = str("phone")
        set(v) = put("phone", v)

    var companyName: String?
        get() = str("company_name")
        set(v) = put("company_name", v)

    /** epoch ms of the newest call already sent */
    var lastSync: Long
        get() = sp.getLong("last_sync", 0L)
        set(v) { sp.edit().putLong("last_sync", v).apply() }

    /** epoch ms of the last successful upload */
    var lastSyncOk: Long
        get() = sp.getLong("last_sync_ok", 0L)
        set(v) { sp.edit().putLong("last_sync_ok", v).apply() }

    var status: String
        get() = sp.getString("status", "Not synced yet") ?: ""
        set(v) = put("status", v)

    /** Old v4 setting: 0 = SIM 1, 1 = SIM 2 */
    var simSlot: Int
        get() = sp.getInt("sim_slot", 0)
        set(v) { sp.edit().putInt("sim_slot", v).apply() }

    /** 0 = SIM 1, 1 = SIM 2, 2 = both SIMs. Phones upgraded from v4 keep their old SIM. */
    var simMode: Int
        get() = if (sp.contains("sim_mode")) sp.getInt("sim_mode", 0) else simSlot
        set(v) { sp.edit().putInt("sim_mode", v).putInt("sim_slot", if (v == 1) 1 else 0).apply() }

    /** version of the UI downloaded from the server (0 = none) */
    var uiVersion: Int
        get() = sp.getInt("ui_version", 0)
        set(v) { sp.edit().putInt("ui_version", v).apply() }

    // ---- call recordings ----
    var recEnabled: Boolean
        get() = sp.getBoolean("rec_enabled", true)
        set(v) { sp.edit().putBoolean("rec_enabled", v).apply() }

    var recWifiOnly: Boolean
        get() = sp.getBoolean("rec_wifi_only", false)
        set(v) { sp.edit().putBoolean("rec_wifi_only", v).apply() }

    /** false when the company switched recording upload off on the server */
    var recAllowed: Boolean
        get() = sp.getBoolean("rec_allowed", true)
        set(v) { sp.edit().putBoolean("rec_allowed", v).apply() }

    /** what the last recording check found, shown in Settings */
    var recLast: String
        get() = sp.getString("rec_last", "") ?: ""
        set(v) = put("rec_last", v)

    var recUploadedCount: Int
        get() = sp.getInt("rec_count", 0)
        set(v) { sp.edit().putInt("rec_count", v).apply() }

    /** device_call_ids whose recording is uploaded ("u:") or given up ("x:"), newest last */
    fun recDone(): MutableList<String> = arr(str("rec_done"))

    fun setRecDone(list: List<String>) = put("rec_done", JSONArray(list.takeLast(1500)).toString())

    // ---- call notes ----
    @Synchronized
    fun notes(): JSONObject = try { JSONObject(str("notes") ?: "{}") } catch (e: Exception) { JSONObject() }

    @Synchronized
    fun saveNote(id: String, note: String) {
        val n = notes()
        if (note.isBlank()) n.put(id, "") else n.put(id, note.take(1000))
        // keep the file small: only the newest 1500 notes
        if (n.length() > 1500) {
            val keys = n.keys().asSequence().toList()
            keys.take(n.length() - 1500).forEach { n.remove(it) }
        }
        put("notes", n.toString())
        val p = pendingNotes()
        if (id !in p) p.add(id)
        put("pending_notes", JSONArray(p).toString())
    }

    @Synchronized
    fun pendingNotes(): MutableList<String> = arr(str("pending_notes"))

    @Synchronized
    fun clearPendingNotes(done: Collection<String>) {
        val p = pendingNotes()
        p.removeAll(done.toSet())
        put("pending_notes", JSONArray(p).toString())
    }

    private fun arr(s: String?): MutableList<String> {
        val out = mutableListOf<String>()
        try {
            val a = JSONArray(s ?: "[]")
            for (i in 0 until a.length()) out.add(a.getString(i))
        } catch (e: Exception) { }
        return out
    }

    fun clear() { sp.edit().clear().apply() }
}
