package com.renew.jss.permissions

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.annotation.RequiresApi

/**
 * Enterprise permission handler with OEM-specific auto-start handling
 * Matches competitor behavior for granular permission management
 */
class EnterprisePermissionHandler(private val context: Context) {
    
    companion object {
        private const val TAG = "EnterprisePermissions"
        
        // OEM-specific package names for auto-start settings
        private val OEM_AUTO_START_PACKAGES = mapOf(
            "xiaomi" to "com.miui.securitycenter",
            "oppo" to "com.coloros.safecenter",
            "vivo" to "com.iqoo.secure",
            "huawei" to "com.huawei.systemmanager",
            "samsung" to "com.samsung.android.sm",
            "oneplus" to "com.oneplus.security",
            "realme" to "com.realme.securitycenter",
            "asus" to "com.asus.mobilemanager",
            "nokia" to "com.nokia.mobi",
            "motorola" to "com.motorola.motocare"
        )
    }
    
    /**
     * Check if battery optimization is ignored
     */
    fun isIgnoringBatteryOptimizations(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                powerManager.isIgnoringBatteryOptimizations(context.packageName)
            } else {
                true // Not applicable on older versions
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to check battery optimization: ${e.message}")
            false
        }
    }
    
    /**
     * Request to ignore battery optimizations
     */
    fun requestIgnoreBatteryOptimizations() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request battery optimization exemption: ${e.message}")
        }
    }
    
    /**
     * Check if auto-start permission is granted
     * This is complex and varies by OEM - we use a heuristic approach
     */
    fun canAutoStart(): Boolean {
        // Auto-start permission is hard to check programmatically
        // We'll use a heuristic based on manufacturer and known behavior
        val manufacturer = Build.MANUFACTURER.lowercase()
        
        return try {
            when {
                manufacturer.contains("xiaomi") -> checkXiaomiAutoStart()
                manufacturer.contains("oppo") -> checkOppoAutoStart()
                manufacturer.contains("vivo") -> checkVivoAutoStart()
                manufacturer.contains("huawei") -> checkHuaweiAutoStart()
                manufacturer.contains("samsung") -> checkSamsungAutoStart()
                manufacturer.contains("oneplus") -> checkOnePlusAutoStart()
                manufacturer.contains("realme") -> checkRealmeAutoStart()
                else -> true // Assume granted for unknown OEMs
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to check auto-start: ${e.message}")
            false
        }
    }
    
    /**
     * Request auto-start permission (OEM-specific)
     */
    fun requestAutoStart() {
        try {
            val manufacturer = Build.MANUFACTURER.lowercase()
            val intent = getAutoStartIntent(manufacturer)
            
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            } else {
                // Fallback to application settings
                openAppSettings()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request auto-start: ${e.message}")
            openAppSettings()
        }
    }
    
    /**
     * Check if overlay permission is granted
     */
    fun hasOverlayPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true // Not applicable on older versions
        }
    }
    
    /**
     * Request overlay permission
     */
    fun requestOverlayPermission() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request overlay permission: ${e.message}")
        }
    }
    
    /**
     * Check if foreground service permission is available
     */
    fun hasForegroundServicePermission(): Boolean {
        // On Android 10+, foreground service restrictions apply
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Check if app can start foreground services
            try {
                // This is a simplified check - in reality this is complex
                true // Assume granted for now
            } catch (e: Exception) {
                false
            }
        } else {
            true
        }
    }
    
    /**
     * Request foreground service permission
     */
    fun requestForegroundServicePermission() {
        try {
            // Open app settings for foreground service permissions
            openAppSettings()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request foreground service permission: ${e.message}")
        }
    }
    
    /**
     * Check if battery usage permission is granted
     */
    fun hasBatteryPermission(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                // This is a heuristic check - return true if battery optimization is NOT ignored
                powerManager.isIgnoringBatteryOptimizations(context.packageName)
            } else {
                true
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to check battery permission: ${e.message}")
            false
        }
    }
    
    /**
     * Request battery usage permission
     */
    fun requestBatteryPermission() {
        try {
            // Open battery optimization settings
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request battery permission: ${e.message}")
            openAppSettings()
        }
    }
    
    /**
     * Get device manufacturer
     */
    fun getManufacturer(): String {
        return Build.MANUFACTURER.lowercase()
    }
    
    // OEM-specific auto-start check methods
    private fun checkXiaomiAutoStart(): Boolean {
        return try {
            val packageManager = context.packageManager
            val intent = Intent().apply {
                setClassName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
            }
            val resolveInfo = packageManager.resolveActivity(intent, 0)
            resolveInfo != null
        } catch (e: Exception) {
            false
        }
    }
    
    private fun checkOppoAutoStart(): Boolean {
        return try {
            val packageManager = context.packageManager
            val intent = Intent().apply {
                setClassName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity")
            }
            val resolveInfo = packageManager.resolveActivity(intent, 0)
            resolveInfo != null
        } catch (e: Exception) {
            false
        }
    }
    
    private fun checkVivoAutoStart(): Boolean {
        return try {
            val packageManager = context.packageManager
            val intent = Intent().apply {
                setClassName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AutoStartManager")
            }
            val resolveInfo = packageManager.resolveActivity(intent, 0)
            resolveInfo != null
        } catch (e: Exception) {
            false
        }
    }
    
    private fun checkHuaweiAutoStart(): Boolean {
        return try {
            val packageManager = context.packageManager
            val intent = Intent().apply {
                setClassName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity")
            }
            val resolveInfo = packageManager.resolveActivity(intent, 0)
            resolveInfo != null
        } catch (e: Exception) {
            false
        }
    }
    
    private fun checkSamsungAutoStart(): Boolean {
        return try {
            val packageManager = context.packageManager
            val intent = Intent().apply {
                setClassName("com.samsung.android.sm", "com.samsung.android.sm.battery.BatteryActivity")
            }
            val resolveInfo = packageManager.resolveActivity(intent, 0)
            resolveInfo != null
        } catch (e: Exception) {
            false
        }
    }
    
    private fun checkOnePlusAutoStart(): Boolean {
        return try {
            val packageManager = context.packageManager
            val intent = Intent().apply {
                setClassName("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity")
            }
            val resolveInfo = packageManager.resolveActivity(intent, 0)
            resolveInfo != null
        } catch (e: Exception) {
            false
        }
    }
    
    private fun checkRealmeAutoStart(): Boolean {
        return try {
            val packageManager = context.packageManager
            val intent = Intent().apply {
                setClassName("com.realme.securitycenter", "com.realme.securitycenter.backgroundstart.BackgroundStartActivity")
            }
            val resolveInfo = packageManager.resolveActivity(intent, 0)
            resolveInfo != null
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Get OEM-specific auto-start intent
     */
    private fun getAutoStartIntent(manufacturer: String): Intent? {
        return try {
            when {
                manufacturer.contains("xiaomi") -> Intent().apply {
                    setClassName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
                }
                manufacturer.contains("oppo") -> Intent().apply {
                    setClassName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity")
                }
                manufacturer.contains("vivo") -> Intent().apply {
                    setClassName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AutoStartManager")
                }
                manufacturer.contains("huawei") -> Intent().apply {
                    setClassName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity")
                }
                manufacturer.contains("samsung") -> Intent().apply {
                    setClassName("com.samsung.android.sm", "com.samsung.android.sm.battery.BatteryActivity")
                }
                manufacturer.contains("oneplus") -> Intent().apply {
                    setClassName("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity")
                }
                manufacturer.contains("realme") -> Intent().apply {
                    setClassName("com.realme.securitycenter", "com.realme.securitycenter.backgroundstart.BackgroundStartActivity")
                }
                else -> null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get auto-start intent for $manufacturer: ${e.message}")
            null
        }
    }
    
    /**
     * Open app settings
     */
    private fun openAppSettings() {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open app settings: ${e.message}")
        }
    }
}




