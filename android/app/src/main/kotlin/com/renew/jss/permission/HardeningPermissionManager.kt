package com.renew.jss.permission

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.content.ComponentName
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

class HardeningPermissionManager(private val context: Context) {

    companion object {
        private const val PREFS_NAME = "hardening_prefs"
        private const val TAG = "HardeningPermissionManager"
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isPermissionGranted(permission: HardeningPermission): Boolean {
        val manufacturer = Build.MANUFACTURER.lowercase()
        val isOemAggressive = manufacturer.contains("vivo") || 
                             manufacturer.contains("oppo") || 
                             manufacturer.contains("realme")

        return when (permission) {
            HardeningPermission.BATTERY_OPTIMIZATION -> {
                val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                val isStandardGranted = pm.isIgnoringBatteryOptimizations(context.packageName)
                
                // 🛡️ OEM FIX: On Vivo/Oppo/Realme/Xiaomi, the standard flag often fails even when allowed.
                // We turn the tile green if the standard check passes OR if we've already sent the user to settings.
                if (isOemAggressive || manufacturer.contains("xiaomi") || manufacturer.contains("redmi")) {
                    isStandardGranted || prefs.getBoolean("battery_optimization_prompt_shown", false)
                } else {
                    isStandardGranted
                }
            }
            HardeningPermission.OVERLAY -> {
                Settings.canDrawOverlays(context)
            }
            HardeningPermission.BATTERY_POWER_USAGE ->
                prefs.getBoolean("battery_power_usage_prompt_shown", false)

            HardeningPermission.AUTO_START ->
                prefs.getBoolean("auto_start_prompt_shown", false)

            HardeningPermission.FLOATING_WINDOWS ->
                prefs.getBoolean("floating_windows_prompt_shown", false)
                
            HardeningPermission.DEVICE_ADMIN -> {
                try {
                    val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                    val admin = ComponentName(context, com.renew.jss.DeviceOwnerReceiver::class.java)
                    dpm.isAdminActive(admin)
                } catch (e: Exception) {
                    Log.e(TAG, "Error checking device admin: ${e.message}")
                    false
                }
            }
            
            HardeningPermission.ACCESSIBILITY -> {
                // 🛡️ DUAL CHECK: Check Settings.Secure + AccessibilityManager
                val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? android.view.accessibility.AccessibilityManager
                val isServiceRunning = am?.isEnabled == true && 
                    am.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_GENERIC)
                      ?.any { it.resolveInfo.serviceInfo.packageName == context.packageName } == true
                
                if (isServiceRunning) return true

                // Fallback to Settings.Secure (standard check)
                val enabledServices = Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                )
                
                // Be very specific with the package name and service name to avoid substring collisions
                val expectedService = ComponentName(context, com.renew.jss.service.MyAccessibilityService::class.java).flattenToString()
                enabledServices != null && (enabledServices.contains(expectedService) || enabledServices.contains(context.packageName))
            }
            
            HardeningPermission.LOCATION -> {
                ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) == 
                       PackageManager.PERMISSION_GRANTED
            }
            
            HardeningPermission.SMS -> {
                ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECEIVE_SMS) == 
                       PackageManager.PERMISSION_GRANTED &&
                       ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_SMS) == 
                       PackageManager.PERMISSION_GRANTED
            }
            
            HardeningPermission.PHONE -> {
                ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_PHONE_STATE) == 
                       PackageManager.PERMISSION_GRANTED &&
                       ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_PHONE_NUMBERS) == 
                       PackageManager.PERMISSION_GRANTED
            }
            
            HardeningPermission.NOTIFICATION -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) == 
                           PackageManager.PERMISSION_GRANTED
                } else {
                    true
                }
            }
        }
    }

    fun markPromptShown(permission: HardeningPermission) {
        val key = when (permission) {
            HardeningPermission.BATTERY_OPTIMIZATION -> "battery_optimization_prompt_shown"
            HardeningPermission.BATTERY_POWER_USAGE -> "battery_power_usage_prompt_shown"
            HardeningPermission.AUTO_START -> "auto_start_prompt_shown"
            HardeningPermission.FLOATING_WINDOWS -> "floating_windows_prompt_shown"
            else -> null
        }

        key?.let {
            prefs.edit().putBoolean(it, true).apply()
            Log.d(TAG, "Marked prompt shown for $permission")
        }
    }

    fun isHardeningCompleted(): Boolean {
        return prefs.getBoolean("hardening_completed", false)
    }

    fun markHardeningCompleted() {
        prefs.edit().putBoolean("hardening_completed", true).apply()
        Log.d(TAG, "Hardening flow completed")
    }
}




