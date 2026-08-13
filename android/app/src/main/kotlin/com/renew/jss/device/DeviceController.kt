package com.renew.jss.device

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PersistableBundle
import android.util.Log
import com.renew.jss.DeviceOwnerReceiver
import com.renew.jss.receiver.UninstallResultReceiver
import com.renew.jss.storage.LockedStateStore
import com.renew.jss.utils.Constants
import java.io.PrintWriter
import java.io.StringWriter

object DeviceController {

    private const val TAG = "DeviceController"

    fun lockDevice(context: Context) {
        try {
            Log.d(TAG, "🔒 SMS: Locking device...")
            
            // 1. Mark device as locked (Legacy storage)
            LockedStateStore.setLocked(context, true)
            
            // 2. Sync with Policy Engine (Prevents auto-revert)
            com.renew.jss.policy.PolicyChangeProcessor.getInstance(context).updatePersistedPolicy("lock_device", true)
            
            // 3. Apply kiosk policy (Actual locking)
            com.renew.jss.policy.PolicyDispatcher.apply(context, "KIOSK", true)
            
            Log.d(TAG, "🔒 SMS: Device locked and policy synced successfully")
        } catch (e: Exception) {
            Log.e(TAG, "🔒 SMS: Failed to lock device", e)
        }
    }

    fun unlockDevice(context: Context) {
        try {
            Log.d(TAG, "🔓 SMS: Unlocking device...")
            
            // 1. Mark device as unlocked (Legacy storage)
            LockedStateStore.setLocked(context, false)
            
            // 2. Sync with Policy Engine (Prevents auto-relock)
            com.renew.jss.policy.PolicyChangeProcessor.getInstance(context).updatePersistedPolicy("lock_device", false)
            
            // 3. Remove kiosk policy (Actual unlocking)
            com.renew.jss.policy.PolicyDispatcher.apply(context, "KIOSK", false)
            
            Log.d(TAG, "🔓 SMS: Device unlocked and policy synced successfully")
        } catch (e: Exception) {
            Log.e(TAG, "🔓 SMS: Failed to unlock device", e)
        }
    }

    // (Removed TestDPC ownership transfer methods and DO-specific setups)

    /**
     * 🎯 MATCH CODEWINT: Initialize Device Policy Manager
     */
    fun init(context: Context) {
        try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val admin = ComponentName(context, DeviceOwnerReceiver::class.java)
            
            Log.d(TAG, "=== DEVICE POLICY MANAGER DEBUG ===")
            Log.d(TAG, "Package name: ${context.packageName}")
            Log.d(TAG, "Admin component: $admin")
            Log.d(TAG, "Is admin active: ${dpm.isAdminActive(admin)}")
            Log.d(TAG, "Is device owner app: ${dpm.isDeviceOwnerApp(context.packageName)}")
            Log.d(TAG, "Is profile owner app: ${dpm.isProfileOwnerApp(context.packageName)}")
            
            // Initialize as Device Admin
            Log.d(TAG, "Initializing Device Admin settings...")
            
            if (!dpm.isAdminActive(admin)) {
                Log.w(TAG, "Device admin is not active")
                return
            }
            
            Log.d(TAG, "Device Policy Manager initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Device Policy Manager", e)
        }
    }

    /**
     * 🎯 MATCH CODEWINT: Setup device policies exactly like them
     */
    fun setup(context: Context) {
        try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val admin = ComponentName(context, DeviceOwnerReceiver::class.java)

            Log.d(TAG, "Device Admin setup completed successfully")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to setup device", e)
        }
    }

}




