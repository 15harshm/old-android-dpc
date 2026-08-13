package com.renew.jss.policy

import android.content.Context
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.util.Log
import com.renew.jss.DeviceOwnerReceiver

/**
 * 🚀 OPTIMIZED: PolicyDispatcher with cached DPM and ComponentName for performance
 */
object PolicyDispatcher {

    private const val TAG = "FCMPC_PolicyDispatcher"
    
    // 🚀 PERFORMANCE: Cache DPM and ComponentName to avoid repeated getSystemService calls
    @Volatile
    private var devicePolicyManager: DevicePolicyManager? = null
    
    @Volatile
    private var adminComponent: ComponentName? = null
    
    /**
     * 🚀 OPTIMIZED: Initialize cached objects once
     */
    private fun initializeCachedObjects(context: Context) {
        if (devicePolicyManager == null) {
            synchronized(this) {
                if (devicePolicyManager == null) {
                    try {
                        devicePolicyManager = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
                        adminComponent = ComponentName(context, DeviceOwnerReceiver::class.java)
                        Log.d(TAG, "🚀 PolicyDispatcher initialized with cached objects")
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Failed to initialize PolicyDispatcher: ${e.message}", e)
                        throw e
                    }
                }
            }
        }
    }

    fun apply(
        context: Context,
        policy: String,
        enable: Boolean
    ) {
        Log.d(TAG, "🔥 apply → policy=$policy enable=$enable")

        // 🚀 OPTIMIZATION: Initialize cached objects on first use
        initializeCachedObjects(context)

        try {
            when (policy) {

                "CAMERA" -> {
                    Log.d(TAG, "📷 About to apply CAMERA policy - enable: $enable")
                    try {
                        if (enable) {
                            CameraPolicy.enable(context)
                        } else {
                            CameraPolicy.disable(context)
                        }
                        Log.d(TAG, "✅ CAMERA policy applied successfully")
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Policy apply failed: CAMERA", e)
                        // 🎯 CRITICAL FIX: Don't crash - just log and continue
                    }
                }

                "KIOSK" -> {
                    Log.d(TAG, "🔍 KIOSK policy received - enable: $enable")
                    if (enable) {
                        Log.d(TAG, "🔒 About to call KioskPolicy.enter()")
                        try {
                            KioskPolicy.enter(context)
                            Log.d(TAG, "✅ KioskPolicy.enter() completed successfully")
                        } catch (e: Exception) {
                            Log.e(TAG, "❌ KioskPolicy.enter() failed: ${e.message}", e)
                            throw e
                        }
                    } else {
                        Log.d(TAG, "🔓 About to call KioskPolicy.exit()")
                        try {
                            KioskPolicy.exit(context)
                            Log.d(TAG, "✅ KioskPolicy.exit() completed successfully")
                        } catch (e: Exception) {
                            Log.e(TAG, "❌ KioskPolicy.exit() failed: ${e.message}", e)
                            throw e
                        }
                    }
                }

                "LOCATION" -> {
                    if (enable) {
                        Log.d(TAG, "soc-loc 📍 Calling LocationPolicy.enable()")
                        LocationPolicy.enable(context)
                    } else {
                        Log.d(TAG, "soc-loc 📍 Calling LocationPolicy.disable()")
                        LocationPolicy.disable(context)
                    }
                }

                "WALLPAPER" -> {
                    if (enable) {
                        Log.d(TAG, "🖼️ Calling WallpaperPolicy.set()")
                        WallpaperPolicy.set(context)
                    } else {
                        Log.d(TAG, "🗑️ Calling WallpaperPolicy.unset()")
                        WallpaperPolicy.unset(context)
                    }
                }

                "REMOVE_ALL_RESTRICTIONS" -> {
                    if (enable) {
                        Log.d(TAG, "🔓 Calling RemoveAllRestrictionsPolicy.removeAll()")
                        RemoveAllRestrictionsPolicy.removeAll(context)
                    } else {
                        Log.d(TAG, "🔒 REMOVE_ALL_RESTRICTIONS policy called with false - no action needed")
                    }
                }

                "APP_VISIBILITY" -> {
                    // Hide or show the app launcher icon via PackageManager.
                    // enable=true  → hide icon (alias disabled)
                    // enable=false → show icon (alias enabled, default)
                    // MainActivity, all services, and FCM are NOT affected.
                    if (enable) {
                        Log.d(TAG, "👁️ APP_VISIBILITY: Hiding app launcher icon")
                        AppVisibilityPolicy.hide(context)
                    } else {
                        Log.d(TAG, "👁️ APP_VISIBILITY: Showing app launcher icon")
                        AppVisibilityPolicy.show(context)
                    }
                }

                else -> {
                    Log.w(TAG, "Unknown policy: $policy")
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Policy apply failed: $policy", e)
        }
    }
}




