package com.example.callsync

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.telephony.TelephonyManager
import org.json.JSONObject

/** Battery, network, model and permission state — sent with every sync so the company sees phone health. */
object DeviceInfo {

    private fun has(ctx: Context, p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    fun battery(ctx: Context): Int? = try {
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).takeIf { it in 0..100 }
    } catch (e: Exception) { null }

    fun charging(ctx: Context): Boolean? = try {
        (ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager).isCharging
    } catch (e: Exception) { null }

    /** "wifi", "5G", "4G", "3G", "2G", "mobile", "ethernet" or "none" */
    @SuppressLint("MissingPermission")
    fun network(ctx: Context): String {
        return try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return "none"
            when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> {
                    if (!has(ctx, Manifest.permission.READ_PHONE_STATE)) return "mobile"
                    val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
                    when (tm.dataNetworkType) {
                        TelephonyManager.NETWORK_TYPE_NR -> "5G"
                        TelephonyManager.NETWORK_TYPE_LTE -> "4G"
                        TelephonyManager.NETWORK_TYPE_HSPAP, TelephonyManager.NETWORK_TYPE_HSPA,
                        TelephonyManager.NETWORK_TYPE_HSDPA, TelephonyManager.NETWORK_TYPE_HSUPA,
                        TelephonyManager.NETWORK_TYPE_UMTS, TelephonyManager.NETWORK_TYPE_EVDO_0,
                        TelephonyManager.NETWORK_TYPE_EVDO_A, TelephonyManager.NETWORK_TYPE_EVDO_B,
                        TelephonyManager.NETWORK_TYPE_EHRPD, TelephonyManager.NETWORK_TYPE_TD_SCDMA -> "3G"
                        TelephonyManager.NETWORK_TYPE_GPRS, TelephonyManager.NETWORK_TYPE_EDGE,
                        TelephonyManager.NETWORK_TYPE_CDMA, TelephonyManager.NETWORK_TYPE_1xRTT,
                        TelephonyManager.NETWORK_TYPE_IDEN, TelephonyManager.NETWORK_TYPE_GSM -> "2G"
                        else -> "mobile"
                    }
                }
                else -> "other"
            }
        } catch (e: Exception) { "unknown" }
    }

    fun isWifi(ctx: Context): Boolean = try {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        caps != null && (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
    } catch (e: Exception) { false }

    fun appVersion(ctx: Context): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: ""
    } catch (e: Exception) { "" }

    fun audioPermission(): String =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
        else Manifest.permission.READ_EXTERNAL_STORAGE

    fun json(ctx: Context): JSONObject {
        val sims = SimInfo.list(ctx)
        val p = Prefs(ctx)
        return JSONObject().apply {
            battery(ctx)?.let { put("battery", it) }
            charging(ctx)?.let { put("charging", it) }
            put("network", network(ctx))
            put("model", (Build.MANUFACTURER.replaceFirstChar { it.uppercase() } + " " + Build.MODEL).take(100))
            put("android", Build.VERSION.RELEASE)
            put("app", appVersion(ctx))
            sims.getOrNull(0)?.name?.let { put("sim1", it) }
            sims.getOrNull(1)?.name?.let { put("sim2", it) }
            put("perm_call_log", has(ctx, Manifest.permission.READ_CALL_LOG))
            put("perm_contacts", has(ctx, Manifest.permission.READ_CONTACTS))
            put("perm_recording", has(ctx, audioPermission()))
            put("rec_enabled", p.recEnabled)
        }
    }
}
