package com.renew.jss.policy

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.util.Log
import com.renew.jss.DeviceOwnerReceiver


object CameraPolicy {

    private const val TAG = "CameraPolicy"

    fun disable(context: Context) {
        try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val admin = ComponentName(context, DeviceOwnerReceiver::class.java)

            // 🎯 CRITICAL FIX: Check if admin is active before applying policy
            if (!dpm.isAdminActive(admin)) {
                Log.w(TAG, "⚠️ Device Admin not active - cannot disable camera")
                return
            }

            // 🎯 CRITICAL FIX: Check if we have permission to manage camera
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                try {
                    dpm.setCameraDisabled(admin, true)
                    Log.d(TAG, "✅ Camera disabled successfully")
                } catch (e: SecurityException) {
                    Log.e(TAG, "❌ No permission to disable camera: ${e.message}")
                    // 🎯 CRITICAL FIX: Don't crash - just log and continue
                }
            } else {
                Log.w(TAG, "⚠️ Camera policy not supported on this Android version")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to disable camera: ${e.message}", e)
        }
    }

    fun enable(context: Context) {
        try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val admin = ComponentName(context, DeviceOwnerReceiver::class.java)

            // 🎯 CRITICAL FIX: Check if admin is active before applying policy
            if (!dpm.isAdminActive(admin)) {
                Log.w(TAG, "⚠️ Device Admin not active - cannot enable camera")
                return
            }

            // 🎯 CRITICAL FIX: Check if we have permission to manage camera
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                try {
                    dpm.setCameraDisabled(admin, false)
                    Log.d(TAG, "✅ Camera enabled successfully")
                } catch (e: SecurityException) {
                    Log.e(TAG, "❌ No permission to enable camera: ${e.message}")
                    // 🎯 CRITICAL FIX: Don't crash - just log and continue
                }
            } else {
                Log.w(TAG, "⚠️ Camera policy not supported on this Android version")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to enable camera: ${e.message}", e)
        }
    }
}






