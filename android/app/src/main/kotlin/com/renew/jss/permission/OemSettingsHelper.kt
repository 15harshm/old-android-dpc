package com.renew.jss.permission

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import android.provider.Settings


object OemSettingsHelper {

    private const val TAG = "OemSettingsHelper"

    fun requestPermission(
        activity: Activity,
        permission: HardeningPermission
    ) {
        when (permission) {
            HardeningPermission.BATTERY_OPTIMIZATION -> {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                intent.data = Uri.parse("package:${activity.packageName}")
                activity.startActivity(intent)
                markPromptShown(activity, permission)
                Log.d(TAG, "Opening battery optimization settings")
            }

            HardeningPermission.OVERLAY -> {
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                intent.data = Uri.parse("package:${activity.packageName}")
                activity.startActivity(intent)
                Log.d(TAG, "Opening overlay permission settings")
            }

            HardeningPermission.BATTERY_POWER_USAGE -> {
                openBatteryUsageSettings(activity)
                markPromptShown(activity, permission)
            }

            HardeningPermission.AUTO_START -> {
                openAutoStartSettings(activity)
                markPromptShown(activity, permission)
            }

            HardeningPermission.FLOATING_WINDOWS -> {
                openFloatingWindowSettings(activity)
                markPromptShown(activity, permission)
            }

            // New permissions - handled elsewhere
            HardeningPermission.DEVICE_ADMIN -> {
                Log.d(TAG, "Device Admin permission handled by MainActivity")
            }

            HardeningPermission.ACCESSIBILITY -> {
                Log.d(TAG, "Accessibility permission handled by MainActivity")
            }

            HardeningPermission.LOCATION -> {
                Log.d(TAG, "Location permission handled by MainActivity")
            }

            HardeningPermission.SMS -> {
                Log.d(TAG, "SMS permission handled by MainActivity")
            }

            HardeningPermission.PHONE -> {
                Log.d(TAG, "Phone permission handled by MainActivity")
            }

            HardeningPermission.NOTIFICATION -> {
                Log.d(TAG, "Notification permission handled by MainActivity")
            }
        }
    }

    private fun openBatteryUsageSettings(activity: Activity) {
        val manufacturer = Build.MANUFACTURER.lowercase()

        when (manufacturer) {
            "xiaomi" -> openXiaomiSettings(activity, "com.renew.jssenter")
            "oppo" -> openOppoSettings(activity, "com.coloros.powermanager")
            "vivo" -> openVivoSettings(activity, "com.vivo.abe")
            "realme" -> openRealmeSettings(activity, "com.oplus.powermanager")
            "huawei" -> openHuaweiSettings(activity, "com.huawei.systemmanager")
            else -> openGenericSettings(activity)
        }
    }

    private fun openAutoStartSettings(activity: Activity) {
        val manufacturer = Build.MANUFACTURER.lowercase()

        when (manufacturer) {
            "xiaomi" -> openXiaomiSettings(activity, "com.miui.securitycenter")
            "oppo" -> openOppoSettings(activity, "com.coloros.securitycenter")
            "vivo" -> openVivoSettings(activity, "com.vivo.securityassistant")
            "realme" -> openRealmeSettings(activity, "com.oplus.securitycenter")
            "huawei" -> openHuaweiSettings(activity, "com.huawei.systemmanager")
            else -> openGenericSettings(activity)
        }
    }

    private fun openFloatingWindowSettings(activity: Activity) {
        val manufacturer = Build.MANUFACTURER.lowercase()

        when (manufacturer) {
            "xiaomi" -> openXiaomiSettings(activity, "com.miui.securitycenter")
            "oppo" -> openOppoSettings(activity, "com.coloros.securitycenter")
            "vivo" -> openVivoSettings(activity, "com.vivo.securityassistant")
            "realme" -> openRealmeSettings(activity, "com.oplus.securitycenter")
            "huawei" -> openHuaweiSettings(activity, "com.huawei.systemmanager")
            else -> openGenericSettings(activity)
        }
    }

    private fun openXiaomiSettings(activity: Activity, packageName: String) {
        try {
            val intent = Intent()
            intent.setClassName("com.miui.securitycenter", "com.miui.securitycenter.MainActivity")
            activity.startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Could not open Xiaomi settings: $packageName")
            openGenericSettings(activity)
        }
    }

    private fun openOppoSettings(activity: Activity, packageName: String) {
        try {
            val intent = Intent()
            intent.setClassName("com.coloros.securitycenter", "com.coloros.securitycenter.MainActivity")
            activity.startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Could not open Oppo settings: $packageName")
            openGenericSettings(activity)
        }
    }

    private fun openVivoSettings(activity: Activity, packageName: String) {
        try {
            val intent = Intent()
            intent.setClassName("com.vivo.securityassistant", "com.vivo.securityassistant.MainActivity")
            activity.startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Could not open Vivo settings: $packageName")
            openGenericSettings(activity)
        }
    }

    private fun openRealmeSettings(activity: Activity, packageName: String) {
        try {
            val intent = Intent()
            intent.setClassName("com.oplus.securitycenter", "com.oplus.securitycenter.MainActivity")
            activity.startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Could not open Realme settings: $packageName")
            openGenericSettings(activity)
        }
    }

    private fun openHuaweiSettings(activity: Activity, packageName: String) {
        try {
            val intent = Intent()
            intent.setClassName("com.huawei.systemmanager", "com.huawei.systemmanager.MainActivity")
            activity.startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Could not open Huawei settings: $packageName")
            openGenericSettings(activity)
        }
    }

    private fun openGenericSettings(activity: Activity) {
        try {
            val intent = Intent(Settings.ACTION_SETTINGS)
            activity.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Could not open generic settings", e)
        }
    }

    private fun markPromptShown(activity: Activity, permission: HardeningPermission) {
        val manager = HardeningPermissionManager(activity)
        manager.markPromptShown(permission)
    }
}




