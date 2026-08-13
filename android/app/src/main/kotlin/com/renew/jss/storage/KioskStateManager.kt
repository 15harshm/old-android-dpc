package com.renew.jss.storage

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * 🔒 KIOSK STATE MANAGER - Network-Independent Lock Persistence
 * 
 * Manages kiosk mode state independently of network connectivity.
 * Ensures device remains locked even when WiFi disconnects or services restart.
 */
object KioskStateManager {

    private const val TAG = "KioskStateManager"
    private const val PREFS_NAME = "kiosk_state_manager"
    private const val KEY_KIOSK_ENABLED = "kiosk_enabled"
    private const val KEY_KIOSK_TIMESTAMP = "kiosk_timestamp"
    private const val KEY_LAST_ENFORCEMENT = "last_enforcement_timestamp"
    private const val KEY_ENFORCEMENT_COUNT = "enforcement_count"

    /**
     * 🔒 SET KIOSK STATE - Network Independent
     */
    fun setKioskEnabled(context: Context, enabled: Boolean) {
        try {
            val prefs = getPrefs(context)
            val editor = prefs.edit()
            
            editor.putBoolean(KEY_KIOSK_ENABLED, enabled)
            editor.putLong(KEY_KIOSK_TIMESTAMP, System.currentTimeMillis())
            editor.putLong(KEY_LAST_ENFORCEMENT, System.currentTimeMillis())
            editor.putInt(KEY_ENFORCEMENT_COUNT, if (enabled) 1 else 0)
            
            editor.apply()
            
            Log.d(TAG, "🔒 Kiosk state set to: $enabled at ${System.currentTimeMillis()}")
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to set kiosk state: ${e.message}", e)
        }
    }

    /**
     * 🔍 IS KIOSK ENABLED - Network Independent Check
     */
    fun isKioskEnabled(context: Context): Boolean {
        return try {
            val prefs = getPrefs(context)
            val isEnabled = prefs.getBoolean(KEY_KIOSK_ENABLED, false)
            Log.d(TAG, "🔍 Kiosk enabled check: $isEnabled")
            isEnabled
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to check kiosk state: ${e.message}", e)
            false
        }
    }

    /**
     * 📊 GET KIOSK STATE INFO
     */
    fun getKioskStateInfo(context: Context): KioskStateInfo {
        return try {
            val prefs = getPrefs(context)
            KioskStateInfo(
                isEnabled = prefs.getBoolean(KEY_KIOSK_ENABLED, false),
                timestamp = prefs.getLong(KEY_KIOSK_TIMESTAMP, 0),
                lastEnforcement = prefs.getLong(KEY_LAST_ENFORCEMENT, 0),
                enforcementCount = prefs.getInt(KEY_ENFORCEMENT_COUNT, 0)
            )
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to get kiosk state info: ${e.message}", e)
            KioskStateInfo()
        }
    }

    /**
     * 🔄 RECORD ENFORCEMENT ACTION
     */
    fun recordEnforcement(context: Context) {
        try {
            val prefs = getPrefs(context)
            val currentCount = prefs.getInt(KEY_ENFORCEMENT_COUNT, 0)
            
            prefs.edit()
                .putLong(KEY_LAST_ENFORCEMENT, System.currentTimeMillis())
                .putInt(KEY_ENFORCEMENT_COUNT, currentCount + 1)
                .apply()
                
            Log.d(TAG, "🔄 Enforcement recorded. Count: ${currentCount + 1}")
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to record enforcement: ${e.message}", e)
        }
    }

    /**
     * 🧹 CLEAR KIOSK STATE
     */
    fun clearKioskState(context: Context) {
        try {
            val prefs = getPrefs(context)
            prefs.edit()
                .remove(KEY_KIOSK_ENABLED)
                .remove(KEY_KIOSK_TIMESTAMP)
                .remove(KEY_LAST_ENFORCEMENT)
                .remove(KEY_ENFORCEMENT_COUNT)
                .apply()
                
            Log.d(TAG, "🧹 Kiosk state cleared")
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to clear kiosk state: ${e.message}", e)
        }
    }

    /**
     * 🔧 VALIDATE KIOSK STATE
     * Checks if kiosk state is consistent and valid
     */
    fun validateKioskState(context: Context): Boolean {
        return try {
            val info = getKioskStateInfo(context)
            val currentTime = System.currentTimeMillis()
            
            // Check if state is recent (within last 24 hours)
            val isRecent = (currentTime - info.timestamp) < (24 * 60 * 60 * 1000)
            
            // Check if enforcement is recent (within last 15 minutes)
            val isEnforcementRecent = (currentTime - info.lastEnforcement) < (15 * 60 * 1000)
            
            val isValid = info.isEnabled && isRecent
            
            Log.d(TAG, "🔧 Kiosk state validation: enabled=${info.isEnabled}, recent=$isRecent, enforcementRecent=$isEnforcementRecent, valid=$isValid")
            
            isValid
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to validate kiosk state: ${e.message}", e)
            false
        }
    }

    /**
     * 🔄 SYNC WITH LOCKED STATE
     * Synchronizes kiosk state with LockedStateStore
     */
    fun syncWithLockedState(context: Context) {
        try {
            val isLocked = LockedStateStore.isLocked(context)
            val isKioskEnabled = isKioskEnabled(context)
            
            if (isLocked && !isKioskEnabled) {
                Log.d(TAG, "🔄 Sync: Device locked but kiosk not enabled - enabling kiosk")
                setKioskEnabled(context, true)
            } else if (!isLocked && isKioskEnabled) {
                Log.d(TAG, "🔄 Sync: Device unlocked but kiosk enabled - disabling kiosk")
                setKioskEnabled(context, false)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to sync with locked state: ${e.message}", e)
        }
    }

    /**
     * 📱 GET PREFERENCES
     */
    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * 📊 KIOSK STATE INFO DATA CLASS
     */
    data class KioskStateInfo(
        val isEnabled: Boolean = false,
        val timestamp: Long = 0,
        val lastEnforcement: Long = 0,
        val enforcementCount: Int = 0
    ) {
        fun getFormattedTimestamp(): String {
            return if (timestamp > 0) {
                java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                    .format(java.util.Date(timestamp))
            } else {
                "Never"
            }
        }
        
        fun getFormattedLastEnforcement(): String {
            return if (lastEnforcement > 0) {
                java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                    .format(java.util.Date(lastEnforcement))
            } else {
                "Never"
            }
        }
    }
}
