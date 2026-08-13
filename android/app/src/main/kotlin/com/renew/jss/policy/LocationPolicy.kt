package com.renew.jss.policy

import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.renew.jss.DeviceOwnerReceiver

object LocationPolicy {

    private const val TAG = "LocationPolicy"

    fun enable(context: Context) {
        try {
            Log.d(TAG, "soc-loc 📍 Attempting to enable location...")
            
            val admin = ComponentName(context, DeviceOwnerReceiver::class.java)
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
            
            // Method 1: Clear user restriction (like competitor) - Android 28+
            // (Removed as it requires Device Owner)

            // Method 2: Grant location permissions to our app (Device Admin can do this)
            // (Removed setPermissionGrantState as it requires Device Owner)

            // Method 3: Direct location enable (like competitor)
            // (Removed setLocationEnabled as it requires Device Owner)
            
            // Method 4: Settings.Secure fallback
            try {
                Settings.Secure.putInt(
                    context.contentResolver,
                    Settings.Secure.LOCATION_MODE,
                    Settings.Secure.LOCATION_MODE_HIGH_ACCURACY
                )
                Log.d(TAG, "soc-loc 📍 Location mode set to HIGH_ACCURACY")
            } catch (e: Exception) {
                Log.w(TAG, "soc-loc ⚠️ Settings.Secure failed: ${e.message}")
            }
            
            Log.d(TAG, "soc-loc ✅ Location enable sequence completed")
            
        } catch (e: Exception) {
            Log.e(TAG, "soc-loc ❌ Failed to enable location: ${e.message}", e)
        }
    }

    fun disable(context: Context) {
        try {
            Log.d(TAG, "soc-loc 📍 Attempting to disable location...")
            
            // Method 1: Set location mode to off (primary method)
            Settings.Secure.putInt(
                context.contentResolver,
                Settings.Secure.LOCATION_MODE,
                Settings.Secure.LOCATION_MODE_OFF
            )
            Log.d(TAG, "soc-loc 📍 Location mode set to OFF")
            
            // Method 2: Try Device Admin direct location control
            // (Removed setLocationEnabled as it requires Device Owner)
            
            Log.d(TAG, "soc-loc ✅ Location disabled successfully")
            
        } catch (e: Exception) {
            Log.e(TAG, "soc-loc ❌ Failed to disable location: ${e.message}", e)
        }
    }

    fun isLocationEnabled(context: Context): Boolean {
        return try {
            val locationMode = Settings.Secure.getInt(
                context.contentResolver,
                Settings.Secure.LOCATION_MODE,
                Settings.Secure.LOCATION_MODE_OFF
            )
            locationMode != Settings.Secure.LOCATION_MODE_OFF
        } catch (e: Exception) {
            Log.e(TAG, "soc-loc ❌ Failed to check location status: ${e.message}", e)
            false
        }
    }
}




