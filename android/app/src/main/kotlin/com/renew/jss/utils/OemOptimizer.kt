package com.renew.jss.utils

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * 🚀 OEM Optimizer - Solves Background Death on Chinese Devices
 * 
 * Specifically targets Xiaomi/Redmi, Oppo, Vivo, and Huawei.
 * Guides users to enable Autostart and disable Battery Optimization.
 */
object OemOptimizer {
    
    private const val TAG = "OemOptimizer"

    fun isXiaomi(): Boolean {
        return Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true) || 
               Build.BRAND.equals("Xiaomi", ignoreCase = true) ||
               Build.BRAND.equals("Redmi", ignoreCase = true)
    }

    /**
     * Open Autostart settings for Xiaomi
     */
    fun openXiaomiAutostart(context: Context) {
        try {
            val intent = Intent()
            intent.component = ComponentName(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity"
            )
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open Xiaomi Autostart: ${e.message}")
            try {
                // Fallback 1
                val intent = Intent()
                intent.component = ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity"
                )
                context.startActivity(intent)
            } catch (e2: Exception) {
                // Fallback 2: Open App Info
                openAppInfo(context)
            }
        }
    }

    /**
     * Open Battery Optimization settings for Xiaomi
     */
    fun openXiaomiBatteryOptimization(context: Context) {
        try {
            val intent = Intent()
            intent.component = ComponentName(
                "com.miui.securitycenter",
                "com.miui.powercenter.PowerSettings"
            )
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open Xiaomi Battery Settings: ${e.message}")
            openAppInfo(context)
        }
    }

    fun openAppInfo(context: Context) {
        try {
            val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            intent.data = android.net.Uri.fromParts("package", context.packageName, null)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open App Info: ${e.message}")
        }
    }

    /**
     * 📱 VIVO: Open High Background Power Consumption
     */
    fun openVivoOptimization(context: Context) {
        try {
            val intent = Intent()
            intent.component = ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity")
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            context.startActivity(intent)
        } catch (e: Exception) {
            try {
                val intent = Intent()
                intent.component = ComponentName("com.vivo.abe", "com.vivo.applicationbehaviorengine.ui.ExcessivePowerManagerActivity")
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(intent)
            } catch (e2: Exception) {
                // Fallback to App Info
                openAppInfo(context)
            }
        }
    }

    /**
     * 📱 OPPO / REALME: Open Startup Manager
     */
    fun openOppoOptimization(context: Context) {
        try {
            val intent = Intent()
            intent.component = ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity")
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            context.startActivity(intent)
        } catch (e: Exception) {
            try {
                val intent = Intent()
                intent.component = ComponentName("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity")
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(intent)
            } catch (e2: Exception) {
                // Fallback to App Info
                openAppInfo(context)
            }
        }
    }
}
