package com.example.callsync

import android.content.Context
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** আজকের check-in/out সময় phone-এ রাখা হয়, যাতে app খুললেই দেখানো যায়। */
object AttendanceStore {
    private fun sp(c: Context) = c.getSharedPreferences("callsync", Context.MODE_PRIVATE)

    fun todayIst(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("Asia/Kolkata") }.format(Date())

    fun save(c: Context, checkIn: String?, checkOut: String?) {
        sp(c).edit()
            .putString("att_date", todayIst())
            .putString("att_in", checkIn)
            .putString("att_out", checkOut)
            .apply()
    }

    fun todayJson(c: Context): JSONObject {
        val p = sp(c)
        val same = p.getString("att_date", null) == todayIst()
        val inT: Any = (if (same) p.getString("att_in", null) else null) ?: JSONObject.NULL
        val outT: Any = (if (same) p.getString("att_out", null) else null) ?: JSONObject.NULL
        return JSONObject().put("in", inT).put("out", outT)
    }
}
