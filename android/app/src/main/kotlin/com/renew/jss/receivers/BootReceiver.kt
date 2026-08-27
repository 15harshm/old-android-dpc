package com.renew.jss.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import com.renew.jss.service.PolicyMonitoringService
import com.renew.jss.workers.FcmHeartbeatWorker
import com.renew.jss.storage.LockedStateStore
import com.renew.jss.DeviceOwnerReceiver

/**
 * 🚀 Boot Receiver - Like competitor BootReceiver
 * 
 * Purpose:
 * 1. Start services on device boot
 * 2. Ensure kiosk mode after restart
 * 3. Start heartbeat worker
 * 4. Maintain device lock state
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "FCMPC_BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "🚀 Boot receiver triggered: ${intent.action}")
        
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON" -> {
                val pending = goAsync()
                try {
                    handleBootCompleted(context)
                } finally {
                    // ♿ Give the OS a few seconds to rebind the (enabled) accessibility
                    // service after boot. Aggressive OEMs (iQOO/Vivo, MIUI/POCO) may NOT
                    // rebind it — leaving App Info -> Uninstall unguarded right after a
                    // reboot. If it's still off, force the user to re-enable it via the
                    // full-screen enforcement activity. On normal devices accessibility
                    // is back within this window, so nothing is shown.
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        try {
                            recoverAccessibilityIfDisabled(context)
                        } catch (e: Exception) {
                            Log.e(TAG, "❌ Accessibility recovery check failed: ${e.message}")
                        } finally {
                            pending.finish()
                        }
                    }, 7000)
                }
            }
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_PACKAGE_REPLACED -> {
                handleAppUpdate(context)
            }
        }
    }

    /**
     * ♿ Post-boot safety net: if Device Admin + setup are active but our accessibility
     * service did NOT come back after reboot (common on iQOO/Vivo and MIUI/POCO due to
     * their power management), the App Info / uninstall guards are dead. Launch the
     * enforcement activity to force the user to re-enable it. Only acts when the service
     * is genuinely disabled; the activity self-dismisses once it's restored (no loop),
     * and nothing runs during normal use since this is boot-only.
     */
    private fun recoverAccessibilityIfDisabled(context: Context) {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(context, DeviceOwnerReceiver::class.java)
        if (!dpm.isAdminActive(admin)) return

        val prefs = context.getSharedPreferences("device_admin_setup", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("setup_completed", false)) return

        if (isAccessibilityServiceEnabled(context)) {
            Log.d(TAG, "♿ Accessibility active after boot — no recovery needed")
            return
        }

        Log.w(TAG, "🚨 Accessibility DISABLED after boot — launching enforcement to force re-enable")
        try {
            val enforceIntent = Intent(context, com.renew.jss.activity.PermissionEnforcementActivity::class.java)
            enforceIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            enforceIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            context.startActivity(enforceIntent)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to launch enforcement activity: ${e.message}")
        }
    }

    /**
     * Dual check: AccessibilityManager (actually running) + Settings.Secure (enabled).
     * On any error, assume enabled so we never show enforcement on a false positive.
     */
    private fun isAccessibilityServiceEnabled(context: Context): Boolean {
        return try {
            val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE)
                    as? android.view.accessibility.AccessibilityManager
            val running = am?.isEnabled == true &&
                am.getEnabledAccessibilityServiceList(
                    android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_GENERIC
                )?.any { it.resolveInfo.serviceInfo.packageName == context.packageName } == true
            if (running) return true

            val expected = ComponentName(
                context, com.renew.jss.service.MyAccessibilityService::class.java
            ).flattenToString()
            val enabled = android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
            enabled != null && (enabled.contains(expected) || enabled.contains(context.packageName))
        } catch (e: Exception) {
            true // don't show enforcement if we genuinely can't tell
        }
    }

    private fun handleBootCompleted(context: Context) {
        try {
            Log.d(TAG, "🚀 Handling boot completed")
            
            // Check if app is device admin and setup is completed
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val admin = ComponentName(context, DeviceOwnerReceiver::class.java)
            
            if (dpm.isAdminActive(admin)) {
                val prefs = context.getSharedPreferences("device_admin_setup", Context.MODE_PRIVATE)
                val isSetupCompleted = prefs.getBoolean("setup_completed", false)
                
                if (isSetupCompleted) {
                    Log.d(TAG, "✅ Device admin setup completed - starting all monitoring services")
                    
                    // ✅ START ALL CORE SERVICES
                    val services = listOf(
                        com.renew.jss.service.PolicyMonitoringService::class.java,
                        com.renew.jss.service.AlwaysAliveService::class.java,
                        com.renew.jss.service.PreventiveService::class.java
                    )

                    for (serviceClass in services) {
                        try {
                            val serviceIntent = Intent(context, serviceClass)
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                                context.startForegroundService(serviceIntent)
                            } else {
                                context.startService(serviceIntent)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "❌ Failed to start service ${serviceClass.simpleName}: ${e.message}")
                        }
                    }
                    
                    // ✅ Start FCM Heartbeat Worker
                    FcmHeartbeatWorker.startHeartbeat(context)
                    Log.d(TAG, "✅ FCM Heartbeat worker started on boot")

                    // ✅ Start Periodic Policy Sync (WorkerManager Fallback)
                    com.renew.jss.service.PolicyCheckWorker.schedulePeriodicCheck(context)
                    Log.d(TAG, "✅ PolicyCheckWorker scheduled on boot")
                    
                    // ✅ Re-apply kiosk if device was locked
                    if (LockedStateStore.isLocked(context)) {
                        Log.d(TAG, "🔒 Device was locked - re-enforcing kiosk")
                        try {
                            val kioskIntent = Intent(context, com.renew.jss.activity.KioskActivity::class.java)
                            kioskIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            kioskIntent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                            kioskIntent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
                            context.startActivity(kioskIntent)
                            Log.d("BootReceiver", "✅ KioskActivity started on boot")
                        } catch (e: Exception) {
                            Log.e("BootReceiver", "❌ Failed to start KioskActivity: ${e.message}")
                        }
                    }
                } else {
                    Log.d(TAG, "⏭️ Device admin setup not completed - skipping service start on boot")
                }
            } else {
                Log.d(TAG, "⏭️ App is not device admin - skipping service start on boot")
            }
            
            Log.d(TAG, "✅ Boot completed handling finished")
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to handle boot completed: ${e.message}")
        }
    }

    private fun handleAppUpdate(context: Context) {
        try {
            Log.d(TAG, "🔄 Handling app update/replacement")
            
            // Check if app is device admin and setup is completed
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val admin = ComponentName(context, DeviceOwnerReceiver::class.java)
            
            if (dpm.isAdminActive(admin)) {
                val prefs = context.getSharedPreferences("device_admin_setup", Context.MODE_PRIVATE)
                val isSetupCompleted = prefs.getBoolean("setup_completed", false)
                
                if (isSetupCompleted) {
                    Log.d(TAG, "✅ Device admin setup completed - restarting services after update")
                    
                    // ✅ Restart services after app update
                    val services = listOf(
                        com.renew.jss.service.PolicyMonitoringService::class.java,
                        com.renew.jss.service.AlwaysAliveService::class.java,
                        com.renew.jss.service.PreventiveService::class.java
                    )

                    for (serviceClass in services) {
                        try {
                            val serviceIntent = Intent(context, serviceClass)
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                                context.startForegroundService(serviceIntent)
                            } else {
                                context.startService(serviceIntent)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "❌ Failed to restart service ${serviceClass.simpleName}: ${e.message}")
                        }
                    }
                    
                    // ✅ Restart heartbeat worker
                    FcmHeartbeatWorker.startHeartbeat(context)
                } else {
                    Log.d(TAG, "⏭️ Device admin setup not completed - skipping service restart after update")
                }
            } else {
                Log.d(TAG, "⏭️ App is not device admin - skipping service restart after update")
            }
            
            Log.d(TAG, "✅ App update handling completed")
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to handle app update: ${e.message}")
        }
    }
}
