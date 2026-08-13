package com.renew.jss.policy

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.telephony.TelephonyManager
import android.util.Log
import com.renew.jss.DeviceOwnerReceiver
import com.renew.jss.ApiConfig
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit


/**
 * PolicyChangeProcessor - Enterprise Policy Engine
 * 
 * Applies policies ONLY when they change, avoiding redundant DPM calls
 * and maintaining battery-safe, socket-safe behavior.
 * 
 * 🚀 OPTIMIZED: Cached DPM and ComponentName for performance
 * 🎯 SINGLETON: Prevents multiple instances causing race conditions
 */
class PolicyChangeProcessor private constructor(private val context: Context) {
    
    companion object {
        const val TAG = "FCMPC_PolicyChangeProcessor"
        private const val PREFS_NAME = "policy_processor"
        private const val KEY_LAST_APPLIED_STATE = "last_applied_state"
        private const val KEY_LAST_PAYLOAD_HASH = "last_payload_hash"
        private const val KEY_LAST_PROCESS_TIME = "last_process_time"
        
        // 🎯 SINGLETON: Prevent multiple instances
        @Volatile
        private var INSTANCE: PolicyChangeProcessor? = null
        private val instanceLock = Any()
        
        // 🎯 GLOBAL LOCK: Prevent simultaneous processing
        @Volatile
        private var isProcessing = false
        private val processingLock = Any()
        
        fun getInstance(context: Context): PolicyChangeProcessor {
            return INSTANCE ?: synchronized(instanceLock) {
                INSTANCE ?: PolicyChangeProcessor(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
    
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    
    // 🚀 PERFORMANCE: Cache DPM and ComponentName to avoid repeated getSystemService calls
    private val devicePolicyManager: DevicePolicyManager? by lazy {
        try {
            context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to get DevicePolicyManager: ${e.message}", e)
            null
        }
    }
    
    private val adminComponent: ComponentName by lazy {
        ComponentName(context, DeviceOwnerReceiver::class.java)
    }
    
    // Track actually applied policies (not just received flags)
    private val actuallyAppliedPolicies = mutableMapOf<String, Boolean>()
    
    /**
     * 🚀 OPTIMIZED: Process incoming policy payload with direct JSON processing and batch application
     */
    fun processPolicyPayload(payload: JSONObject, commandId: String = "0") {
        // 🎯 GLOBAL LOCK: Prevent simultaneous processing
        synchronized(processingLock) {
            if (isProcessing) {
                Log.w(TAG, "FCMPC ⏭️ Already processing policy - skipping")
                return
            }
            
            isProcessing = true
        }
        
        try {
            Log.d(TAG, "FCMPC 🔄 Processing policy payload (Command ID: $commandId)")
            
            // 🎯 FIX: Better duplicate detection with timestamp
            val currentTime = System.currentTimeMillis()
            val lastProcessTime = prefs.getLong(KEY_LAST_PROCESS_TIME, 0)
            val minProcessInterval = 5000 // 5 seconds minimum between processes
            
            // Check if we recently processed the same command
            if (currentTime - lastProcessTime < minProcessInterval) {
                Log.w(TAG, "FCMPC ⏭️ Throttling duplicate process - too soon (${currentTime - lastProcessTime}ms ago)")
                return
            }
            
            // Save current process time
            prefs.edit().putLong(KEY_LAST_PROCESS_TIME, currentTime).apply()
            
            // Prevent duplicate payload re-processing
            // 🎯 FIX: Include commandId in hash to allow re-sending same payload with new ID
            val payloadHash = (payload.toString() + commandId).hashCode()
            val lastHash = prefs.getInt(KEY_LAST_PAYLOAD_HASH, 0)
            
            if (payloadHash == lastHash && commandId != "0") {
                Log.w(TAG, "FCMPC ⏭️ Duplicate payload with same ID detected (hash: $payloadHash), skipping")
                return
            }
            
            // Save current payload hash
            prefs.edit().putInt(KEY_LAST_PAYLOAD_HASH, payloadHash).apply()
            Log.d(TAG, "FCMPC 🆔 New payload hash: $payloadHash")
            
            // Clear stale applied policies to prevent accumulation
            actuallyAppliedPolicies.clear()
            
            // 🚀 SIMPLIFIED: Apply ALL policies from payload - no change detection needed
            Log.d(TAG, "FCMPC � Applying all policies from payload (Command ID: $commandId)")
            
            // Apply all policies directly from payload
            val policiesToApply = mutableListOf<Pair<String, Boolean>>()
            
            // 🎯 SYNC WITH WORKING BUILD: Only essential keys for stability
            val relevantKeys = listOf(
                "lock_device",
                "get_location",
                "set_wallpaper",
                "hide_application"  // Remote launcher icon hide/show (no Device Owner needed)
            )
            
            for (key in relevantKeys) {
                if (payload.has(key)) {
                    val value = payload.optBoolean(key, false)
                    policiesToApply.add(Pair(key, value))
                    Log.d(TAG, "RunningDPC: 🔄 Applying policy: $key = $value")
                }
            }
            
            // 🎯 OFFLINE-LOCK SYNC: Map offline_lock to lock_device if present
            if (payload.has("offline_lock") && !payload.has("lock_device")) {
                val value = payload.optBoolean("offline_lock", false)
                policiesToApply.add(Pair("lock_device", value))
                Log.d(TAG, "RunningDPC: 🔄 Mapping offline_lock to lock_device: $value")
            }
            
            if (policiesToApply.isNotEmpty()) {
                Log.d(TAG, "FCMPC � Found ${policiesToApply.size} policies to apply")
                
                // Apply all policies in batches
                applyPoliciesInBatches(policiesToApply)
                
                // Save complete payload state for relevant keys only
                actuallyAppliedPolicies.clear()
                for (key in relevantKeys) {
                    if (payload.has(key)) {
                        actuallyAppliedPolicies[key] = payload.optBoolean(key, false)
                    }
                }
                saveAppliedState()
                
                Log.d(TAG, "FCMPC ✅ All policies applied and saved")
            } else {
                Log.d(TAG, "FCMPC ℹ️ No relevant policies found in payload")
            }
            
            // 🚀 NEW: Update command status to completed
            if (commandId != "0") {
                updateCommandStatus(commandId.toInt(), "completed")
            }
            
            Log.d(TAG, "FCMPC ✅ Policy processing completed")
            
        } catch (e: Exception) {
            Log.e(TAG, "FCMPC ❌ Failed to process policy payload: ${e.message}", e)
        } finally {
            // 🎯 GLOBAL LOCK: Reset processing flag
            synchronized(processingLock) {
                isProcessing = false
                Log.d(TAG, "FCMPC 🔓 Processing lock released")
            }
        }
    }
    
    /**
     * 🚀 NEW: Update command status to backend
     */
    private fun updateCommandStatus(commandId: Int, status: String) {
        try {
            Log.d(TAG, "FCMPC 📤 Updating command $commandId status to: $status")
            
            // Check network availability
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val activeNetwork = connectivityManager.activeNetworkInfo
            val isNetworkAvailable = activeNetwork?.isConnectedOrConnecting == true
            
            if (!isNetworkAvailable) {
                Log.w(TAG, "FCMPC 📶 Network not available. Skipping command status update for command $commandId")
                return
            }
            
            Thread {
                try {
                    val urlString = ApiConfig.Api.UPDATE_COMMAND_STATUS
                    val url = java.net.URL(urlString)
                    val conn = url.openConnection() as java.net.HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.connectTimeout = 3000
                    conn.readTimeout = 3000

                    // Create JSON Body
                    val jsonBody = org.json.JSONObject()
                    jsonBody.put("command_id", commandId)
                    jsonBody.put("status", status)

                    // Send Data
                    val os = conn.outputStream
                    os.write(jsonBody.toString().toByteArray())
                    os.flush()
                    os.close()

                    val responseCode = conn.responseCode
                    Log.d(TAG, "FCMPC 📤 Command status update response: $responseCode")
                    
                    if (responseCode == 200) {
                        Log.d(TAG, "FCMPC ✅ Command $commandId status updated successfully")
                    } else {
                        Log.w(TAG, "FCMPC ⚠️ Command status update failed with response: $responseCode")
                    }
                    
                } catch (e: Exception) {
                    Log.e(TAG, "FCMPC ❌ Failed to update command status: ${e.message}")
                }
            }.start()
            
        } catch (e: Exception) {
            Log.e(TAG, "FCMPC ❌ Error in updateCommandStatus: ${e.message}")
        }
    }
    
    /**
     * Extract relevant policy flags from JSON payload
     */
    private fun extractPolicyMap(payload: JSONObject): Map<String, Boolean> {
        val policies = mutableMapOf<String, Boolean>()
        
        // Only extract relevant policy flags that are present in payload
        val relevantKeys = listOf(
            "lock_device",
            "camera_enabled", 
            "calls_enabled",
            "social_apps_enabled",
            "wifi_enabled",
            "usb_debugging_enabled",
            "factory_reset_enabled",
            "get_location",
            "set_wallpaper"
        )
        
        for (key in relevantKeys) {
            if (payload.has(key)) {
                policies[key] = payload.getBoolean(key)
                Log.d(TAG, "📊 Found policy in payload: $key = ${payload.getBoolean(key)}")
            }
        }
        
        Log.d(TAG, "📊 Extracted policies: $policies")
        return policies
    }
    
    /**
     * Find policies that have changed since last application
     */
    private fun findChangedPolicies(
        newPolicies: Map<String, Boolean>,
        lastApplied: Map<String, Boolean>
    ): Map<String, Boolean> {
        val changed = mutableMapOf<String, Boolean>()
        
        for ((key, newValue) in newPolicies) {
            val oldValue = lastApplied[key]
            
            // Apply if value changed or never applied before
            if (oldValue == null || oldValue != newValue) {
                changed[key] = newValue
                Log.d(TAG, "🔄 Policy changed: $key = $newValue (was $oldValue)")
            }
        }
        
        // IMPORTANT: Don't revert policies not mentioned in current payload
        // Only apply changes for explicitly mentioned keys
        
        return changed
    }
    
    /**
     * Apply changed policies through PolicyDispatcher
     */
    private fun applyChangedPolicies(changedPolicies: Map<String, Boolean>) {
        for ((key, value) in changedPolicies) {
            try {
                when (key) {
                    "lock_device" -> {
                        // Kiosk is bidirectional - apply both true and false
                        Log.d(TAG, "🔒 Applying KIOSK = $value")
                        PolicyDispatcher.apply(context, "KIOSK", value)
                        actuallyAppliedPolicies[key] = value
                    }
                    
                    "get_location" -> {
                        Log.d(TAG, "soc-loc 📍 Processing get_location policy: $value")
                        PolicyDispatcher.apply(context, "LOCATION", value)
                        actuallyAppliedPolicies["get_location"] = value
                        Log.d(TAG, "soc-loc 📍 Location policy applied: $value")
                    }
                    
                    "set_wallpaper" -> {
                        if (value) {
                            // Backend flag true = set wallpaper
                            Log.d(TAG, "🖼️ Setting wallpaper")
                            PolicyDispatcher.apply(context, "WALLPAPER", true)
                            actuallyAppliedPolicies[key] = true
                        } else {
                            // Backend flag false = unset wallpaper
                            Log.d(TAG, "🗑️ Removing wallpaper")
                            PolicyDispatcher.apply(context, "WALLPAPER", false)
                            actuallyAppliedPolicies[key] = false
                        }
                    }
                    
                    else -> {
                        Log.w(TAG, "⚠️ Unknown policy key: $key")
                    }
                }
                
                if (actuallyAppliedPolicies.containsKey(key)) {
                    Log.d(TAG, "✅ Applied policy: $key")
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to apply policy $key: ${e.message}", e)
            }
        }
    }
    
    /**
     * Get last applied policy state from SharedPreferences
     */
    private fun getLastAppliedState(): Map<String, Boolean> {
        val stateJson = prefs.getString(KEY_LAST_APPLIED_STATE, "{}")
        return try {
            val jsonObject = JSONObject(stateJson ?: "{}")
            val state = mutableMapOf<String, Boolean>()
            
            val keys = jsonObject.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                state[key] = jsonObject.optBoolean(key, false)
            }
            
            state
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to parse last applied state: ${e.message}")
            emptyMap()
        }
    }
    
    /**
     * Save current applied state to SharedPreferences
     */
    private fun saveAppliedState() {
        try {
            // Only save actually applied policies, not all received flags
            val jsonObject = JSONObject()
            for ((key, value) in actuallyAppliedPolicies) {
                jsonObject.put(key, value)
            }
            
            prefs.edit()
                .putString(KEY_LAST_APPLIED_STATE, jsonObject.toString())
                .apply()
                
            Log.d(TAG, "💾 Saved applied state: ${actuallyAppliedPolicies.size} enforced policies")
            Log.d(TAG, "💾 Applied state details: $actuallyAppliedPolicies")
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to save applied state: ${e.message}")
        }
    }
    
    /**
     * Update a specific policy in the persisted state manually
     * (e.g. used when device is unlocked locally via PIN to prevent auto-relock)
     */
    fun updatePersistedPolicy(key: String, value: Boolean) {
        try {
            Log.d(TAG, "💾 Updating persisted policy manually: $key = $value")
            
            actuallyAppliedPolicies[key] = value
            
            val stateJson = prefs.getString(KEY_LAST_APPLIED_STATE, "{}")
            val jsonObject = JSONObject(stateJson ?: "{}")
            
            jsonObject.put(key, value)
            
            prefs.edit()
                .putString(KEY_LAST_APPLIED_STATE, jsonObject.toString())
                .remove(KEY_LAST_PAYLOAD_HASH) // Clear hash so backend overrides can be accepted in future if needed
                .apply()
                
            Log.d(TAG, "💾 Successfully updated persisted policy manually: $key = $value")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to update persisted policy: ${e.message}")
        }
    }
    
    /**
     * Reapply persisted policies after device reboot
     * Called from service onCreate or boot receiver
     */
    fun reapplyPersistedPolicies() {
        try {
            Log.d(TAG, "🔄 Reapplying persisted policies...")
            
            val lastAppliedState = getLastAppliedState()
            if (lastAppliedState.isEmpty()) {
                Log.d(TAG, "📋 No persisted policies found")
                return
            }
            
            Log.d(TAG, "🔄 Found ${lastAppliedState.size} persisted policies")
            
            // Clear current state and reapply from persisted
            actuallyAppliedPolicies.clear()
            
            // Reapply only essential persisted keys
            for ((key, value) in lastAppliedState) {
                when (key) {
                    "lock_device", "offline_lock" -> {
                        // 🎯 SYNC: Treat both as KIOSK state
                        val currentKioskState = KioskPolicy.isKioskActive(context)
                        if ((value && currentKioskState) || (!value && !currentKioskState)) {
                            Log.d(TAG, "🔄 Skipping KIOSK reapplication - already in desired state: $value")
                            actuallyAppliedPolicies["lock_device"] = value
                        } else {
                            Log.d(TAG, "🔒 Re-applying KIOSK = $value (current: $currentKioskState)")
                            PolicyDispatcher.apply(context, "KIOSK", value)
                            actuallyAppliedPolicies["lock_device"] = value
                            Log.d(TAG, "✅ Reapplied KIOSK state: $value")
                        }
                    }
                    "get_location" -> {
                        Log.d(TAG, "soc-loc 📍 Re-applying LOCATION = $value")
                        PolicyDispatcher.apply(context, "LOCATION", value)
                        actuallyAppliedPolicies[key] = value
                    }
                    "set_wallpaper" -> {
                        Log.d(TAG, "🖼️ Re-applying WALLPAPER = $value")
                        PolicyDispatcher.apply(context, "WALLPAPER", value)
                        actuallyAppliedPolicies[key] = value
                    }
                    "hide_application" -> {
                        // Safety net: PackageManager already persists component state across
                        // reboots automatically, but we sync our policy state map here.
                        Log.d(TAG, "👁️ Re-applying APP_VISIBILITY (hide_application) = $value")
                        PolicyDispatcher.apply(context, "APP_VISIBILITY", value)
                        actuallyAppliedPolicies[key] = value
                    }
                    else -> {
                        Log.w(TAG, "⚠️ Unknown persisted policy: $key")
                    }
                }
            }
            
            Log.d(TAG, "✅ Policy reapplication completed")
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to reapply persisted policies: ${e.message}", e)
        }
    }
    
    /**
     * Reset all policies (for testing or emergency)
     */
    fun resetAllPolicies() {
        Log.w(TAG, "🔄 Resetting all policies...")
        
        try {
            PolicyDispatcher.apply(context, "KIOSK", false)        // Exit kiosk mode
            PolicyDispatcher.apply(context, "LOCATION", false)      // Remove location restriction
            PolicyDispatcher.apply(context, "WALLPAPER", false)    // Remove wallpaper restriction
            
            // Clear saved state
            prefs.edit()
                .remove(KEY_LAST_APPLIED_STATE)
                .remove(KEY_LAST_PAYLOAD_HASH)
                .apply()
                
            Log.d(TAG, "✅ Essential policies reset")
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to reset policies: ${e.message}")
        }
    }
    
    /**
     * Get current applied state for debugging
     */
    fun getCurrentState(): Map<String, Boolean> {
        return actuallyAppliedPolicies.toMap()
    }
    
    /**
     * 🚀 OPTIMIZED: Find changed policies by processing JSONObject directly
     * Skip intermediate Map conversion for better performance
     */
    private fun findChangedPoliciesDirect(payload: JSONObject, lastAppliedState: Map<String, Boolean>): List<Pair<String, Boolean>> {
        val changedPolicies = mutableListOf<Pair<String, Boolean>>()
        
        Log.d(TAG, "RunningDPC: 🔍 findChangedPoliciesDirect - Last applied state: $lastAppliedState")
        Log.d(TAG, "RunningDPC: 🔍 findChangedPoliciesDirect - Incoming payload: $payload")
        
        // Define essential policy keys to check
        val relevantKeys = listOf(
            "lock_device",
            "get_location",
            "set_wallpaper",
            "hide_application"  // Remote launcher icon hide/show (no Device Owner needed)
        )
        
        // Process each relevant key directly from JSONObject
        for (key in relevantKeys) {
            if (payload.has(key)) {
                val newValue = payload.optBoolean(key, false)
                val oldValue = lastAppliedState[key] ?: false
                
                Log.d(TAG, "RunningDPC: 🔍 Comparing $key: old=$oldValue, new=$newValue")
                
                if (newValue != oldValue) {
                    changedPolicies.add(Pair(key, newValue))
                    Log.d(TAG, "🔄 Policy change detected: $key = $oldValue → $newValue")
                } else {
                    Log.d(TAG, "RunningDPC: ℹ️ No change for $key: $newValue")
                }
            }
        }
        
        Log.d(TAG, "RunningDPC: 📋 Total changed policies: ${changedPolicies.size}")
        return changedPolicies
    }
    
    /**
     * 🚀 OPTIMIZED: Apply policies in batches for better performance
     * Group similar policies and apply them together
     */
    private fun applyPoliciesInBatches(changedPolicies: List<Pair<String, Boolean>>) {
        try {
            // Batch 1: Essential policies (Kiosk, Location, Wallpaper, App Visibility)
            val essentialPolicies = changedPolicies.filter { 
                it.first in listOf("lock_device", "get_location", "set_wallpaper", "hide_application") 
            }
            
            // Apply essential policies
            if (essentialPolicies.isNotEmpty()) {
                Log.d(TAG, "🚀 Applying essential policies batch (${essentialPolicies.size} policies)")
                applyPolicyBatch(essentialPolicies)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to apply policies in batches: ${e.message}", e)
        }
    }
    
    /**
     * 🚀 OPTIMIZED: Apply a batch of policies efficiently
     */
    private fun applyPolicyBatch(policies: List<Pair<String, Boolean>>) {
        for ((key, value) in policies) {
            try {
                Log.d(TAG, "FCMPC_PolicyChangeProcessor: 🔍 Applying policy: $key with value: $value")
                when (key) {
                    "lock_device" -> {
                        if (value) {
                            Log.d(TAG, "🔒 Applying KIOSK = true")
                            PolicyDispatcher.apply(context, "KIOSK", true)
                            actuallyAppliedPolicies[key] = true
                        } else {
                            Log.d(TAG, "🔓 Removing KIOSK restriction")
                            PolicyDispatcher.apply(context, "KIOSK", false)
                            actuallyAppliedPolicies[key] = false
                        }
                    }
                    
                    
                    "camera_enabled" -> {
                        if (value) {
                            Log.d(TAG, "📷 Applying CAMERA = false (restriction)")
                            PolicyDispatcher.apply(context, "CAMERA", false)
                            actuallyAppliedPolicies[key] = true
                        } else {
                            Log.d(TAG, "📷 Removing CAMERA restriction (enable)")
                            PolicyDispatcher.apply(context, "CAMERA", true)
                            actuallyAppliedPolicies[key] = false
                        }
                    }
                    
                    "get_location" -> {
                        Log.d(TAG, "soc-loc 📍 Processing get_location policy: $value")
                        PolicyDispatcher.apply(context, "LOCATION", value)
                        actuallyAppliedPolicies["get_location"] = value
                        Log.d(TAG, "soc-loc 📍 Location policy applied: $value")
                    }
                    
                    "set_wallpaper" -> {
                        if (value) {
                            // Backend flag true = set wallpaper
                            Log.d(TAG, "🖼️ Setting wallpaper")
                            PolicyDispatcher.apply(context, "WALLPAPER", true)
                            actuallyAppliedPolicies[key] = true
                        } else {
                            // Backend flag false = unset wallpaper
                            Log.d(TAG, "🗑️ Removing wallpaper")
                            PolicyDispatcher.apply(context, "WALLPAPER", false)
                            actuallyAppliedPolicies[key] = false
                        }
                    }

                    "hide_application" -> {
                        // true  = hide launcher icon, false = show launcher icon
                        // Uses PackageManager.setComponentEnabledSetting() — no Device Owner needed.
                        // MainActivity, services, and FCM are completely unaffected.
                        Log.d(TAG, "FCMPC_PolicyChangeProcessor: 👁️ Applying hide_application = $value")
                        PolicyDispatcher.apply(context, "APP_VISIBILITY", value)
                        actuallyAppliedPolicies[key] = value
                    }
                    
                    else -> {
                        Log.w(TAG, "⚠️ Unknown policy key: $key")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to apply policy $key: ${e.message}")
            }
        }
    }
}
