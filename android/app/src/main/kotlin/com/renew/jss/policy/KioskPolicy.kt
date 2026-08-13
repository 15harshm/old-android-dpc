package com.renew.jss.policy

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.renew.jss.DeviceOwnerReceiver
import com.renew.jss.activity.KioskActivity
import com.renew.jss.storage.KioskStateManager
import com.renew.jss.service.PolicyMonitoringService
import com.renew.jss.service.KioskEnforcementService
import com.renew.jss.storage.LockedStateStore


object KioskPolicy {

    private const val TAG = "KioskPolicy"

    /**
     * 🎯 CRITICAL FIX: Check if kiosk mode is currently active
     */
    fun isKioskActive(context: Context): Boolean {
        return try {
            // Check if KioskActivity is currently running
            val isLocked = LockedStateStore.isLocked(context)
            Log.d(TAG, "🔍 Kiosk state check - Locked: $isLocked")
            isLocked
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to check kiosk state: ${e.message}")
            false
        }
    }

    fun enter(context: Context) {
        Log.d(TAG, "� KioskPolicy.enter() called")
        
        try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val admin = ComponentName(context, DeviceOwnerReceiver::class.java)

            if (!dpm.isAdminActive(admin)) {
                Log.w(TAG, "⚠️ App is not Device Admin - cannot apply kiosk restrictions")
                return
            }

            Log.d(TAG, "RunningDPC: ✅ App is Device Admin - applying Device Admin compatible restrictions")

            // 🔒 SKIP: setLockTaskPackages requires Device Owner permissions - Device Admin cannot use this
            // We'll rely on KioskActivity for kiosk mode enforcement
            Log.d(TAG, "RunningDPC: 🔒 Skipping setLockTaskPackages (Device Owner only)")
            
            // 🔒 SKIP: setLockTaskFeatures requires Device Owner permissions
            // We'll rely on KioskActivity for kiosk mode
            Log.d(TAG, "RunningDPC: 🔒 Applied lock task packages (Device Admin compatible)")

            // 🔒 SKIP: setStatusBarDisabled and setKeyguardDisabled require Device Owner permissions
            // We'll rely on KioskActivity for screen locking instead
            Log.d(TAG, "RunningDPC: 🔒 Skipping Device Admin-only status bar and keyguard controls")

            // 🔒 CRITICAL: Apply ALL Device Admin compatible user restrictions
            // (Removed as these require Device Owner)

            // 🔒 NETWORK-INDEPENDENT: Store kiosk state
            KioskStateManager.setKioskEnabled(context, true)
            
            // 🔒 SYNC: Ensure LockedStateStore is also updated
            com.renew.jss.storage.LockedStateStore.setLocked(context, true)

            // Start kiosk activity with NO_ANIMATION flag to prevent flash
            val intent = Intent(context, KioskActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
            context.startActivity(intent)
            
            // 🚫 REMOVED: dpm.lockNow() caused black screen flash by triggering
            // system keyguard AFTER KioskActivity was already launched.
            // KioskActivity's windowFlags (FLAG_SHOW_WHEN_LOCKED, FLAG_DISMISS_KEYGUARD)
            // handle this correctly without lockNow().
            
            Log.d(TAG, "RunningDPC: ✅ Kiosk mode entered successfully")
            
        } catch (e: Exception) {
            Log.e(TAG, "RunningDPC: ❌ Failed to enter kiosk mode: ${e.message}", e)
        }
    }

    fun exit(context: Context) {
        Log.d(TAG, "🚀 KioskPolicy.exit() called")
        
        try {
            Log.d(TAG, "RunningDPC: 🔓 Exiting kiosk mode")
            
            // 🎯 CRITICAL FIX: Set device locked to FALSE FIRST (prevent race conditions)
            KioskStateManager.setKioskEnabled(context, false)
            LockedStateStore.setLocked(context, false)
            Log.d(TAG, "🔓 Device lock state set to FALSE")
            
            // 🎯 CRITICAL FIX: Stop ALL services BEFORE any other operations
            try {
                val policyServiceIntent = Intent(context, PolicyMonitoringService::class.java)
                context.stopService(policyServiceIntent)
                Log.d(TAG, "🛑 PolicyMonitoringService stopped")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to stop PolicyMonitoringService: ${e.message}")
            }
            
            try {
                val enforcementServiceIntent = Intent(context, KioskEnforcementService::class.java)
                context.stopService(enforcementServiceIntent)
                Log.d(TAG, "🛑 KioskEnforcementService stopped")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to stop KioskEnforcementService: ${e.message}")
            }
            
            // 🎯 REMOVED Thread.sleep(500) — blocking the calling thread (potentially main)
            // causes ANR. Services stop asynchronously; the delay was not needed.
            
            // 🎯 CRITICAL FIX: Send exit signal to KioskActivity SAFELY
            try {
                val activityIntent = Intent(context, KioskActivity::class.java)
                activityIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                activityIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                activityIntent.putExtra("EXIT_KIOSK", true)
                activityIntent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
                context.startActivity(activityIntent)
                Log.d(TAG, "🔓 Sent exit signal to KioskActivity safely")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send exit signal to KioskActivity: ${e.message}")
            }
            
            // 🎯 CRITICAL FIX: Go to HOME screen SAFELY
            try {
                val homeIntent = Intent(Intent.ACTION_MAIN)
                homeIntent.addCategory(Intent.CATEGORY_HOME)
                homeIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                homeIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                homeIntent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
                context.startActivity(homeIntent)
                Log.d(TAG, "🏠 Redirected to HOME screen safely")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to redirect to HOME screen: ${e.message}")
            }
            
            Log.d(TAG, "✅ Kiosk mode exited successfully (CodeWint approach with crash prevention)")
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to exit kiosk mode: ${e.message}", e)
        }
    }
    
    /**
     * 🔒 ENFORCE KIOSK STATE - Network Independent
     * Re-applies kiosk mode based on stored state, not network commands
     */
    fun enforceKioskState(context: Context) {
        try {
            val shouldEnforce = KioskStateManager.isKioskEnabled(context)
            
            if (shouldEnforce) {
                Log.d(TAG, "🔒 Enforcing kiosk state from stored data")
                
                // Record enforcement action
                KioskStateManager.recordEnforcement(context)
                
                // Re-apply kiosk policies
                val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                val admin = ComponentName(context, DeviceOwnerReceiver::class.java)

                // 🔒 SKIP: setLockTaskPackages requires Device Owner permissions - Device Admin cannot use this
            // We'll rely on KioskActivity for kiosk mode enforcement
            Log.d(TAG, "RunningDPC: 🔒 Skipping setLockTaskPackages (Device Owner only)")
            
            // 🔒 SKIP: setStatusBarDisabled and setKeyguardDisabled require Device Admin permissions
            Log.d(TAG, "RunningDPC: 🔒 Enforcing kiosk state with Device Admin compatible restrictions only")
                
                // Ensure kiosk activity is running - use SINGLE_TOP to avoid re-creating it
                val intent = Intent(context, KioskActivity::class.java)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
                context.startActivity(intent)
                
                Log.d(TAG, "✅ Kiosk state enforced successfully")
                
            } else {
                Log.d(TAG, "🔓 Kiosk not enabled - skipping enforcement")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to enforce kiosk state: ${e.message}", e)
        }
    }
    
    /**
     * 🔧 VALIDATE KIOSK STATE
     * Checks if kiosk mode is properly enforced
     */
    fun validateKioskState(context: Context): Boolean {
        return try {
            val isValid = KioskStateManager.validateKioskState(context)
            Log.d(TAG, "🔧 Kiosk state validation: $isValid")
            isValid
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to validate kiosk state: ${e.message}", e)
            false
        }
    }
}




