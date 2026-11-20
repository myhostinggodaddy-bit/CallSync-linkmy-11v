package com.example.callsync

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.webkit.JavascriptInterface
import androidx.work.WorkManager
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL

/** Methods the HTML screens can call as `Android.xxx()`. */
class Bridge(private val act: MainActivity) {

    companion object { const val BRIDGE_VERSION = 7 }

    private val prefs = Prefs(act)

    private fun has(p: String) = act.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun simsJson(): JSONArray {
        val arr = JSONArray()
        SimInfo.list(act).forEach { s ->
            arr.put(JSONObject().put("slot", s.slot).put("name", s.name ?: JSONObject.NULL).put("active", s.ids.isNotEmpty()))
        }
        return arr
    }

    @JavascriptInterface
    fun getState(): String {
        val pm = act.getSystemService(Context.POWER_SERVICE) as PowerManager
        val perms = JSONObject()
            .put("callLog", has(Manifest.permission.READ_CALL_LOG))
            .put("phone", has(Manifest.permission.READ_PHONE_STATE))
            .put("contacts", has(Manifest.permission.READ_CONTACTS))
            .put("notif", Build.VERSION.SDK_INT < 33 || has(Manifest.permission.POST_NOTIFICATIONS))
            .put("audio", has(DeviceInfo.audioPermission()))
        val host = try { URL(Config.SERVER_URL).host } catch (e: Exception) { "" }
        val device = JSONObject()
            .put("battery", DeviceInfo.battery(act) ?: JSONObject.NULL)
            .put("charging", DeviceInfo.charging(act) ?: JSONObject.NULL)
            .put("network", DeviceInfo.network(act))
            .put("model", Build.MANUFACTURER.replaceFirstChar { it.uppercase() } + " " + Build.MODEL)
            .put("android", Build.VERSION.RELEASE)
        val rec = JSONObject()
            .put("enabled", prefs.recEnabled)
            .put("wifiOnly", prefs.recWifiOnly)
            .put("allowed", prefs.recAllowed)
            .put("uploaded", prefs.recUploadedCount)
            .put("last", prefs.recLast)
        return JSONObject()
            .put("bridge", BRIDGE_VERSION)
            .put("registered", prefs.token != null)
            .put("name", prefs.employeeName ?: "")
            .put("employeeId", prefs.employeeId ?: "")
            .put("phone", prefs.phone ?: "")
            .put("companyName", prefs.companyName ?: "")
            .put("status", prefs.status)
            .put("lastSyncOk", prefs.lastSyncOk)
            .put("perms", perms)
            .put("batteryExempt", pm.isIgnoringBatteryOptimizations(act.packageName))
            .put("host", host)
            .put("version", DeviceInfo.appVersion(act))
            .put("versionCode", ApkUpdater.currentCode(act))
            .put("appUpdate", true)
            .put("uiVersion", UiUpdater.currentVersion(act))
            .put("simSupport", true)
            .put("simSlot", if (prefs.simMode == 1) 1 else 0)
            .put("simMode", prefs.simMode)
            .put("sims", simsJson())
            .put("device", device)
            .put("rec", rec)
            .put("attendance", true)
            .put("att", AttendanceStore.todayJson(act))
            .toString()
    }

    /** Calls of the last [days] days as a JSON array (newest last). */
    @JavascriptInterface
    fun getCalls(days: Int): String {
        val d = days.coerceIn(1, 120)
        val since = System.currentTimeMillis() - d * 86400000L
        return try { CallReader.read(act, since).logs.toString() } catch (e: Exception) { "[]" }
    }

    /** Step 1 of sign-up: check the company invite code. Answers window.onVerify(ok, companyName, error). */
    @JavascriptInterface
    fun verifyCode(code: String) {
        Thread {
            try {
                val base = Config.SERVER_URL.trim().trimEnd('/')
                val res = Api.post("$base/check_code.php", JSONObject().put("invite_code", code.trim()))
                act.js("window.onVerify && window.onVerify(true, ${JSONObject.quote(res.optString("company_name"))}, '')")
            } catch (e: Exception) {
                act.js("window.onVerify && window.onVerify(false, '', ${JSONObject.quote(friendly(e))})")
            }
        }.start()
    }

    /** Step 2: {employee_id, name, phone, invite_code, sim_mode}. Answers window.onRegister(ok, error). */
    @JavascriptInterface
    fun registerV5(json: String) {
        val o = try { JSONObject(json) } catch (e: Exception) { JSONObject() }
        doRegister(
            o.optString("employee_id"), o.optString("name"), o.optString("invite_code"),
            o.optString("phone"), o.optInt("sim_mode", 0)
        )
    }

    // kept for screens from older versions
    @JavascriptInterface
    fun register(empId: String, name: String, code: String) = doRegister(empId, name, code, "", 0)

    @JavascriptInterface
    fun registerWithSim(empId: String, name: String, code: String, simSlot: Int) =
        doRegister(empId, name, code, "", if (simSlot == 1) 1 else 0)

    private fun doRegister(empId: String, name: String, code: String, phone: String, simMode: Int) {
        Thread {
            try {
                val base = Config.SERVER_URL.trim().trimEnd('/')
                val mode = if (simMode in 0..2) simMode else 0
                val body = JSONObject()
                    .put("employee_id", empId.trim())
                    .put("name", name.trim())
                    .put("phone", phone.trim())
                    .put("invite_code", code.trim())
                    .put("sim_mode", mode)
                    .put("device", DeviceInfo.json(act))
                val res = Api.post("$base/register.php", body)
                prefs.baseUrl = base
                prefs.token = res.getString("token")
                prefs.employeeId = empId.trim()
                prefs.employeeName = name.trim()
                prefs.phone = phone.trim()
                prefs.companyName = res.optString("company_name")
                prefs.recAllowed = res.optBoolean("record_calls", true)
                prefs.simMode = mode
                // first run: only upload the last 7 days
                prefs.lastSync = System.currentTimeMillis() - 7L * 24 * 3600 * 1000
                prefs.status = "Registered"
                act.runOnUiThread {
                    SyncWorker.schedule(act)
                    ServiceStarter.start(act)
                }
                act.js("window.onRegister && window.onRegister(true, '')")
            } catch (e: Exception) {
                act.js("window.onRegister && window.onRegister(false, ${JSONObject.quote(friendly(e))})")
            }
        }.start()
    }

    private fun friendly(e: Exception): String {
        val code = (e as? HttpError)?.code ?: 0
        val m = e.message ?: ""
        return when {
            code == 404 || (code == 403 && m.contains("invite", true)) -> "Wrong company code."
            code == 403 && m.contains("Company", true) -> "This company is blocked."
            code == 403 -> "You are blocked by your company."
            code == 409 -> "Your company has reached its caller limit."
            code == 400 -> "Check the Employee ID and Name."
            code >= 500 -> "Server error. Check config.php and the database."
            e is java.net.UnknownHostException || e is java.net.ConnectException ||
                e is java.net.SocketTimeoutException -> "Cannot reach the server. Check your internet."
            else -> "Could not connect: $m"
        }
    }

    @JavascriptInterface
    fun syncNow() {
        Thread {
            val r = SyncEngine.run(act)
            act.js("window.onSync && window.onSync(${r.ok}, ${JSONObject.quote(r.message)})")
        }.start()
    }

    /** Saves a note for one call; it is uploaded right away (or with the next sync). */
    @JavascriptInterface
    fun saveNote(callId: String, note: String) {
        if (callId.isBlank() || callId.length > 64) return
        prefs.saveNote(callId, note.trim())
        Thread { SyncEngine.pushNotes(act) }.start()
    }

    @JavascriptInterface
    fun setRecording(enabled: Boolean, wifiOnly: Boolean) {
        prefs.recEnabled = enabled
        prefs.recWifiOnly = wifiOnly
        if (enabled) prefs.recAllowed = true
        if (enabled && !has(DeviceInfo.audioPermission())) requestAudioPermission()
    }

    @JavascriptInterface
    fun requestAudioPermission() {
        act.runOnUiThread { act.requestPermissions(arrayOf(DeviceInfo.audioPermission()), 2) }
    }

    @JavascriptInterface
    fun requestPermissions() {
        act.runOnUiThread {
            val list = mutableListOf(
                Manifest.permission.READ_CALL_LOG,
                Manifest.permission.READ_PHONE_STATE,
                Manifest.permission.READ_CONTACTS
            )
            if (Build.VERSION.SDK_INT >= 33) list.add(Manifest.permission.POST_NOTIFICATIONS)
            if (prefs.recEnabled) list.add(DeviceInfo.audioPermission())
            act.requestPermissions(list.toTypedArray(), 1)
        }
    }

    @JavascriptInterface
    fun openAppSettings() {
        act.runOnUiThread {
            act.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${act.packageName}"))
            )
        }
    }

    @JavascriptInterface
    fun openBatterySettings() {
        act.runOnUiThread {
            try {
                act.startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${act.packageName}"))
                )
            } catch (e: Exception) {
                try {
                    act.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                } catch (e2: Exception) { }
            }
        }
    }

    @JavascriptInterface
    fun unregister() {
        act.runOnUiThread {
            WorkManager.getInstance(act).cancelAllWork()
            SyncAlarm.cancel(act)
            act.stopService(Intent(act, SyncService::class.java))
            prefs.clear()
            act.js("window.onResume && window.onResume()")
        }
    }

    /** This phone's own attendance (period = day | week | month). Answers window.onAttendance(info). */
    @JavascriptInterface
    fun getAttendance(period: String) {
        Thread {
            val res = try {
                val base = (prefs.baseUrl ?: Config.SERVER_URL).trimEnd('/')
                Api.post("$base/attendance_me.php", JSONObject().put("period", period), prefs.token).toString()
            } catch (e: Exception) {
                JSONObject().put("ok", false).put("error", "Could not load attendance").toString()
            }
            act.js("window.onAttendance && window.onAttendance($res)")
        }.start()
    }

    /** Opens the attendance screen: QR → face → GPS → submit. */
    @JavascriptInterface
    fun openAttendance() {
        act.runOnUiThread { act.startActivity(Intent(act, AttendanceActivity::class.java)) }
    }

    // ---------------- in-app update ----------------

    /** Answers window.onAppUpdate(info) — info.available is true when a newer APK is on the server. */
    @JavascriptInterface
    fun checkAppUpdate() {
        Thread {
            val res = try { ApkUpdater.check(act).put("ok", true) }
                      catch (e: Exception) { JSONObject().put("ok", false).put("error", e.message ?: "Network error") }
            act.js("window.onAppUpdate && window.onAppUpdate(" + res.toString() + ")")
        }.start()
    }

    /** অ্যাপের ভেতরে download + install. Progress: window.onUpdatePhase / onUpdateProgress / onUpdateDone. */
    @JavascriptInterface
    fun downloadAppUpdate(url: String) {
        act.runOnUiThread { ApkInstaller.start(act, url) }
    }

    /** Kept for screens from v6. The app no longer installs files itself. */
    @JavascriptInterface
    fun installAppUpdate(): Boolean = false

    /** Settings → "Check recordings". Answers window.onRecCheck(info). */
    @JavascriptInterface
    fun checkRecordings() {
        Thread {
            val res = try { RecordingSync.diagnose(act) } catch (e: Exception) { JSONObject().put("error", e.message ?: "error") }
            act.js("window.onRecCheck && window.onRecCheck(" + res.toString() + ")")
        }.start()
    }
}
