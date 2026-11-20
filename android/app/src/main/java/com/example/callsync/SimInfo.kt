package com.example.callsync

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.SubscriptionManager

/** The phone's SIM cards and the ids Android may write into CallLog.PHONE_ACCOUNT_ID for each. */
object SimInfo {
    data class Sim(val slot: Int, val name: String?, val ids: Set<String>)

    @SuppressLint("MissingPermission")
    fun list(ctx: Context): List<Sim> {
        val out = mutableListOf<Sim>()
        val ok = ctx.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
        for (slot in 0..1) {
            var name: String? = null
            val ids = mutableSetOf<String>()
            if (ok) try {
                val sm = ctx.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
                sm.getActiveSubscriptionInfoForSimSlotIndex(slot)?.let { info ->
                    name = info.carrierName?.toString()?.takeIf { it.isNotBlank() }
                        ?: info.displayName?.toString()
                    ids.add(info.subscriptionId.toString())
                    info.iccId?.takeIf { it.isNotBlank() }?.let { ids.add(it) }
                }
            } catch (e: Exception) { }
            out.add(Sim(slot, name, ids))
        }
        return out
    }

    /** Which slot a call-log PHONE_ACCOUNT_ID belongs to, or null when unknown. */
    fun slotOf(accountId: String?, sims: List<Sim>): Int? {
        val acc = accountId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        for (s in sims) {
            for (id in s.ids) {
                if (acc == id) return s.slot
                // some phones store the ICCID with or without the trailing check digit / "F"
                if (id.length >= 10 && (acc.startsWith(id) || id.startsWith(acc)) && acc.length >= 10) return s.slot
            }
        }
        // only one SIM inserted: every call is from it
        val active = sims.filter { it.ids.isNotEmpty() }
        return if (active.size == 1) active[0].slot else null
    }
}
