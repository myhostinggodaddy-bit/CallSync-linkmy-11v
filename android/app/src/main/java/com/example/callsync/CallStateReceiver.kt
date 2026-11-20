package com.example.callsync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/**
 * Watches the phone state (RINGING → OFFHOOK → IDLE) to measure how long each call rang.
 * Android does not keep ringing time in the call log, so it is recorded here.
 */
class CallStateReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
        try { CallSessions.onState(ctx, state) } catch (e: Exception) { }
    }
}

object CallSessions {
    /** One finished call: when it started ringing / dialling, when it was answered (incoming only), when it ended. */
    data class Session(val start: Long, val answer: Long, val end: Long, val incoming: Boolean)

    private const val FILE = "callsync_sessions"

    @Synchronized
    fun onState(ctx: Context, state: String) {
        val sp = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val last = sp.getString("last", TelephonyManager.EXTRA_STATE_IDLE)
        if (state == last) return // Android 9+ sends every state twice
        val now = System.currentTimeMillis()
        val e = sp.edit().putString("last", state)
        when (state) {
            TelephonyManager.EXTRA_STATE_RINGING -> {
                if (last == TelephonyManager.EXTRA_STATE_IDLE) {
                    e.putLong("start", now).putLong("answer", 0L).putBoolean("incoming", true)
                }
                // ringing while already in a call (call waiting) is ignored
            }
            TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                if (last == TelephonyManager.EXTRA_STATE_RINGING) {
                    e.putLong("answer", now)
                } else {
                    e.putLong("start", now).putLong("answer", 0L).putBoolean("incoming", false)
                }
            }
            TelephonyManager.EXTRA_STATE_IDLE -> {
                val start = sp.getLong("start", 0L)
                if (start > 0 && now - start < 6 * 3600 * 1000L) {
                    val arr = try { JSONArray(sp.getString("list", "[]")) } catch (x: Exception) { JSONArray() }
                    arr.put(JSONObject()
                        .put("s", start).put("a", sp.getLong("answer", 0L)).put("e", now)
                        .put("i", sp.getBoolean("incoming", false)))
                    val keep = JSONArray()
                    for (k in maxOf(0, arr.length() - 400) until arr.length()) keep.put(arr.get(k))
                    e.putString("list", keep.toString())
                }
                e.putLong("start", 0L)
            }
        }
        e.commit()
    }

    fun all(ctx: Context): List<Session> {
        val sp = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val out = mutableListOf<Session>()
        try {
            val arr = JSONArray(sp.getString("list", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(Session(o.getLong("s"), o.optLong("a"), o.getLong("e"), o.optBoolean("i")))
            }
        } catch (e: Exception) { }
        return out
    }

    /** The session that belongs to a call-log row (its DATE is the ring / dial moment). */
    fun match(list: List<Session>, callDate: Long): Session? =
        list.minByOrNull { abs(it.start - callDate) }?.takeIf { abs(it.start - callDate) <= 10_000 }
}
