package com.renew.jss.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.renew.jss.service.AlwaysAliveService
import com.renew.jss.service.PolicyMonitoringService
import com.renew.jss.service.PreventiveService

/**
 * 🔄 SCREEN ACTION RECEIVER - Persistence Wakeup
 * 
 * Fires on SCREEN_ON, SCREEN_OFF, and USER_PRESENT.
 * Ensures services are revived every time the user interacts with the phone.
 */
class ScreenActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.d("ScreenActionReceiver", "🔄 Received action: $action")

        // 🚫 SCREEN_OFF: No need to restart services when screen turns off
        // Doing work here adds system load exactly when the OS is about to sleep
        if (action == Intent.ACTION_SCREEN_OFF) return

        // 🎯 Only revive on USER_PRESENT (actual unlock) or SCREEN_ON
        try {
            val prefs = context.getSharedPreferences("device_admin_setup", Context.MODE_PRIVATE)
            if (!prefs.getBoolean("setup_completed", false)) return

            // 🎯 KEY FIX: Delay service revival by 600ms AFTER the unlock event.
            // Starting foreground services immediately on USER_PRESENT interrupts
            // the Android unlock animation and causes a visible black frame flash.
            // The 600ms delay lets the launcher fully render before we do any work.
            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            handler.postDelayed({
                try {
                    // Only start PolicyMonitoringService if it's not already alive
                    if (!PolicyMonitoringService.isRunning) {
                        val monitoringIntent = Intent(context, PolicyMonitoringService::class.java)
                        context.startForegroundService(monitoringIntent)
                        Log.d("ScreenActionReceiver", "✅ PolicyMonitoringService revived")
                    } else {
                        Log.d("ScreenActionReceiver", "⏭️ PolicyMonitoringService already running - skipping")
                    }

                    // Always ensure AlwaysAliveService is running (it's the heartbeat)
                    val aliveIntent = Intent(context, AlwaysAliveService::class.java)
                    context.startForegroundService(aliveIntent)

                    Log.d("ScreenActionReceiver", "✅ Core services revived via Screen Action (delayed)")
                } catch (e: Exception) {
                    Log.e("ScreenActionReceiver", "❌ Failed to revive services: ${e.message}")
                }
            }, 600L) // 600ms delay - unlock animation completes in ~400ms

        } catch (e: Exception) {
            Log.e("ScreenActionReceiver", "❌ Failed to schedule service revival: ${e.message}")
        }
    }


    /**
     * Check if our accessibility service is actually enabled
     */
    private fun isAccessibilityServiceEnabled(context: Context): Boolean {
        try {
            val expectedService = android.content.ComponentName(context, com.renew.jss.service.MyAccessibilityService::class.java).flattenToString()
            val enabledServices = android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            return enabledServices != null && (enabledServices.contains(expectedService) || enabledServices.contains(context.packageName))
        } catch (e: Exception) {
            return false
        }
    }
}
