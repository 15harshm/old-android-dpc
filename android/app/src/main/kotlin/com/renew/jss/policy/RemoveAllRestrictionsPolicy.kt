package com.renew.jss.policy

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.util.Log
import com.renew.jss.DeviceOwnerReceiver
import com.renew.jss.storage.KioskStateManager
import com.renew.jss.ApiConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

object RemoveAllRestrictionsPolicy {

    private const val TAG = "RemoveAllRestrictionsPolicy"

    /**
     * Remove ALL restrictions from the device
     * This method clears all applied policies and restores device to unrestricted state
     */
    fun removeAll(context: Context) {
        try {
            Log.d(TAG, "🔓 Removing ALL restrictions from device")

            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val admin = ComponentName(context, DeviceOwnerReceiver::class.java)

            // ♿ Disable the accessibility service FIRST — it runs the enforcement watchdog
            // (ensureServicesAlive) and blocks Settings/uninstall. Turning it off up front
            // stops it re-enforcing kiosk while we tear everything else down, and must
            // happen before Device Admin is removed below.
            try {
                com.renew.jss.service.MyAccessibilityService.disableService(context)
                Log.d(TAG, "♿ Accessibility service disable requested")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to disable accessibility service: ${e.message}")
            }

            // 🔓 Exit kiosk mode
            try {
                KioskPolicy.exit(context)
                Log.d(TAG, "🔓 Kiosk mode exited")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to exit kiosk mode: ${e.message}")
            }

            // 🔓 Enable camera
            try {
                CameraPolicy.enable(context)
                Log.d(TAG, "📷 Camera enabled")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to enable camera: ${e.message}")
            }

            // (Communication, SocialApps, Network, Usb, FactoryReset policies removed)

            // 🔓 Disable location tracking
            try {
                LocationPolicy.disable(context)
                Log.d(TAG, "📍 Location tracking disabled")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to disable location: ${e.message}")
            }

            // 🔓 Remove wallpaper
            try {
                WallpaperPolicy.unset(context)
                Log.d(TAG, "🖼️ Wallpaper removed")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to remove wallpaper: ${e.message}")
            }

            // 🔓 Device Owner cleanup removed (clearUserRestriction, setLockTaskPackages, etc.)

            // 🔓 Clear kiosk state from storage
            try {
                KioskStateManager.setKioskEnabled(context, false)
                com.renew.jss.storage.LockedStateStore.setLocked(context, false)
                Log.d(TAG, "🔓 Kiosk state cleared from storage")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to clear kiosk state: ${e.message}")
            }

            // 🔓 Do NOT hide DPC app from user so they can uninstall it manually after admin removal
            Log.d(TAG, "✅ ALL restrictions successfully removed (DPC left visible for uninstallation)")
            
            // 📡 Update device status in backend AFTER all restrictions removed but BEFORE device admin removal
            updateDeviceStatus(context)
            
            // Remove Device Admin if active (LAST STEP)
            if (dpm.isAdminActive(admin)) {
                // Exit kiosk mode first
                com.renew.jss.policy.KioskPolicy.exit(context)
                
                // Remove Device Admin
                dpm.removeActiveAdmin(admin)
                Log.d(TAG, "✅ App removed as Device Admin - all restrictions lifted")
                
                // Clear stored states
                val prefs = context.getSharedPreferences("device_admin_setup", Context.MODE_PRIVATE)
                prefs.edit().putBoolean("setup_completed", false).apply()
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to remove all restrictions: ${e.message}", e)
        }
    }
    
    /**
     * Update device status in backend after removing all restrictions
     */
    private fun updateDeviceStatus(context: Context) {
        Log.d(TAG, "🚀 Starting device status update process...")
        
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Log.d(TAG, "📱 Attempting to get device IMEI...")
                val imei = getDeviceImei(context)
                if (imei.isNullOrEmpty()) {
                    Log.e(TAG, "❌ CRITICAL: IMEI not available - cannot update device status")
                    Log.w(TAG, "⚠️ Device status update FAILED - no IMEI")
                    return@launch
                }
                
                Log.i(TAG, "✅ IMEI retrieved successfully: $imei")
                Log.d(TAG, "📡 Preparing to update device status for IMEI: $imei")
                Log.d(TAG, "🌐 API Endpoint: ${ApiConfig.Api.DEVICE_STATUS_UPDATE}")
                
                val client = OkHttpClient()
                Log.d(TAG, "🔧 HTTP Client initialized")
                
                // Create JSON object properly
                Log.d(TAG, "📦 Creating JSON payload...")
                val jsonPayload = JSONObject().apply {
                    put("imei", imei)
                }
                
                val mediaType = "application/json".toMediaType()
                val requestBody = jsonPayload.toString().toRequestBody(mediaType)
                
                Log.d(TAG, "� JSON Payload: ${jsonPayload.toString()}")
                Log.d(TAG, "📋 Request Body: ${requestBody}")
                Log.d(TAG, "🏷️ Media Type: $mediaType")
                
                Log.d(TAG, "🌍 Building HTTP request...")
                val request = Request.Builder()
                    .url(ApiConfig.Api.DEVICE_STATUS_UPDATE)
                    .post(requestBody)
                    .addHeader("Content-Type", "application/json")
                    .build()
                
                Log.d(TAG, "📡 Request URL: ${request.url}")
                Log.d(TAG, "📡 Request Method: ${request.method}")
                Log.d(TAG, "📡 Request Headers: ${request.headers}")
                
                Log.i(TAG, "🚀 Sending device status update request to backend...")
                val startTime = System.currentTimeMillis()
                val response = client.newCall(request).execute()
                val endTime = System.currentTimeMillis()
                
                Log.d(TAG, "⏱️ Request completed in ${endTime - startTime}ms")
                Log.d(TAG, "📊 Response Code: ${response.code}")
                Log.d(TAG, "📊 Response Message: ${response.message}")
                Log.d(TAG, "📊 Response Protocol: ${response.protocol}")
                Log.d(TAG, "📊 Response Headers: ${response.headers}")
                
                if (response.isSuccessful) {
                    val responseBody = response.body?.string()
                    Log.i(TAG, "✅ SUCCESS: Device status updated successfully!")
                    Log.d(TAG, "📥 Response Body: $responseBody")
                    Log.i(TAG, "🎯 Backend notified that device is unlocked")
                } else {
                    Log.e(TAG, "❌ FAILED: Device status update failed!")
                    Log.e(TAG, "📊 Error Response Code: ${response.code}")
                    Log.e(TAG, "📊 Error Response Message: ${response.message}")
                    val errorBody = response.body?.string()
                    Log.e(TAG, "📥 Error Response Body: $errorBody")
                    Log.w(TAG, "⚠️ Backend was NOT notified of device unlock status")
                }
                
                response.close()
                Log.d(TAG, "🔒 Response connection closed")
                
            } catch (e: Exception) {
                Log.e(TAG, "💥 CRITICAL ERROR: Failed to update device status", e)
                Log.e(TAG, "❌ Exception Type: ${e::class.java.simpleName}")
                Log.e(TAG, "❌ Exception Message: ${e.message}")
                Log.e(TAG, "❌ Stack Trace: ${e.stackTraceToString()}")
                Log.w(TAG, "⚠️ Backend was NOT notified due to exception")
            }
        }
    }
    
    /**
     * Get device IMEI from stored SharedPreferences (saved during enrollment)
     */
    private fun getDeviceImei(context: Context): String? {
        return try {
            // Use stored IMEI from SharedPreferences instead of dynamic access
            val imeiPrefs = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
            val storedImei = imeiPrefs.getString("user_imei1", null)
            
            if (!storedImei.isNullOrEmpty()) {
                Log.d(TAG, "✅ Using stored IMEI from SharedPreferences: $storedImei")
                return storedImei
            }
            
            // Fallback: Try to get device IMEI dynamically (may fail due to permissions)
            Log.w(TAG, "⚠️ No stored IMEI found, attempting dynamic access...")
            val telephony = context.getSystemService(Context.TELEPHONY_SERVICE) as android.telephony.TelephonyManager
            
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                val dynamicImei = telephony.imei ?: telephony.deviceId
                if (!dynamicImei.isNullOrEmpty()) {
                    Log.d(TAG, "✅ Using dynamic IMEI: $dynamicImei")
                    return dynamicImei
                }
            } else {
                @Suppress("DEPRECATION")
                val dynamicImei = telephony.deviceId
                if (!dynamicImei.isNullOrEmpty()) {
                    Log.d(TAG, "✅ Using dynamic IMEI (legacy): $dynamicImei")
                    return dynamicImei
                }
            }
            
            Log.e(TAG, "❌ No IMEI available from storage or dynamic access")
            null
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to get IMEI: ${e.message}")
            null
        }
    }

    /**
     * Check if device has any restrictions applied
     * @return true if restrictions are detected, false if device is unrestricted
     */
    fun hasRestrictions(context: Context): Boolean {
        return try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val admin = ComponentName(context, DeviceOwnerReceiver::class.java)
            
            // Check kiosk state
            val isKioskEnabled = KioskStateManager.isKioskEnabled(context)
            
            // Check camera restriction
            val isCameraDisabled = dpm.getCameraDisabled(admin)
            
            val hasAnyRestriction = isKioskEnabled || isCameraDisabled
            
            Log.d(TAG, "🔍 Restrictions check: kiosk=$isKioskEnabled, camera=$isCameraDisabled")
            Log.d(TAG, "🔍 Device has restrictions: $hasAnyRestriction")
            
            hasAnyRestriction
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to check restrictions: ${e.message}", e)
            false // Assume no restrictions if we can't check
        }
    }
}
