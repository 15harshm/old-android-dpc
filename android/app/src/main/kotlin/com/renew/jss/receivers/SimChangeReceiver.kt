package com.renew.jss.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.renew.jss.storage.LockedStateStore
import com.renew.jss.policy.KioskPolicy

/**
 * 📱 SIM CHANGE RECEIVER
 * 
 * Detects SIM removals and changes.
 * Locks the device if a SIM is removed while in locked state.
 */
class SimChangeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action == "android.intent.action.SIM_STATE_CHANGED") {
            val state = intent.getStringExtra("ss") ?: ""
            Log.d("SimChangeReceiver", "📱 SIM State Changed: $state")

            // ABSENT means SIM was removed
            if (state == "ABSENT" || state == "LOCKED") {
                if (LockedStateStore.isLocked(context)) {
                    Log.w("SimChangeReceiver", "🚨 SIM Removed while device locked! Re-enforcing Kiosk...")
                    KioskPolicy.enforceKioskState(context)
                }
            }
        }
    }
}
