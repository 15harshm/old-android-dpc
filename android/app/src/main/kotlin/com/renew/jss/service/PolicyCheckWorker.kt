package com.renew.jss.service

import android.content.Context
import android.content.Intent
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit
import android.util.Log
import com.renew.jss.policy.PolicyChangeProcessor
import com.renew.jss.storage.LockedStateStore
import com.renew.jss.policy.PolicyDispatcher
import android.content.ComponentName
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * 🔄 WORKMANAGER FALLBACK - Like HR Finance competitor
 * 
 * Provides backup policy checking when Socket.IO fails
 * Runs every 15 minutes to ensure policies are applied
 */
class PolicyCheckWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val TAG = "PolicyCheckWorker"
        private const val WORK_NAME = "PolicyCheckWorker"
        
        /**
         * 🚀 SCHEDULE PERIODIC POLICY CHECKS
         */
        fun schedulePeriodicCheck(context: Context) {
            try {
                val constraints = Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()

                val periodicWork = PeriodicWorkRequestBuilder<PolicyCheckWorker>(
                    15, // Repeat every 15 minutes
                    TimeUnit.MINUTES
                )
                    .setConstraints(constraints)
                    .addTag(WORK_NAME)
                    .build()

                WorkManager.getInstance(context)
                    .enqueueUniquePeriodicWork(
                        WORK_NAME,
                        androidx.work.ExistingPeriodicWorkPolicy.KEEP,
                        periodicWork
                    )
                
                Log.d(TAG, "✅ PolicyCheckWorker scheduled every 15 minutes")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to schedule PolicyCheckWorker: ${e.message}")
            }
        }

        /**
         * 🚀 SCHEDULE IMMEDIATE POLICY CHECK
         */
        fun scheduleImmediateCheck(context: Context) {
            try {
                val constraints = Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()

                val immediateWork = OneTimeWorkRequestBuilder<PolicyCheckWorker>()
                    .setConstraints(constraints)
                    .addTag(WORK_NAME)
                    .build()

                WorkManager.getInstance(context)
                    .enqueueUniqueWork(
                        "${WORK_NAME}_immediate",
                        androidx.work.ExistingWorkPolicy.REPLACE,
                        immediateWork
                    )
                
                Log.d(TAG, "✅ Immediate PolicyCheckWorker scheduled")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to schedule immediate PolicyCheckWorker: ${e.message}")
            }
        }
    }

    override suspend fun doWork(): Result {
        return try {
            Log.d(TAG, "🔄 PolicyCheckWorker started - Checking for policy updates...")
            
            // 🎯 CRITICAL: Only run if device admin setup is complete
            val adminPrefs = applicationContext.getSharedPreferences("device_admin_setup", Context.MODE_PRIVATE)
            if (!adminPrefs.getBoolean("setup_completed", false)) {
                Log.d(TAG, "⏭️ Setup not completed - skipping policy check")
                return Result.success()
            }

            // Initialize policy processor
            val policyProcessor = PolicyChangeProcessor.getInstance(applicationContext)
            Log.d(TAG, "✅ PolicyChangeProcessor initialized (singleton)")

            // 🚀 NEW: Fetch latest policies from server via HTTP Polling (Fallback for FCM)
            fetchPoliciesFromServer(policyProcessor)
            
            // Reapply existing policies as fallback
            policyProcessor.reapplyPersistedPolicies()
            
            // 🛡️ SELF-HEALING AUDIT: Check Admin and Accessibility status
            auditHardeningStatus()
            
            // 🚀 RESTART CORE SERVICES: Ensure monitoring is active
            restartCoreServices()
            
            // 🔒 SPECIFIC KIOSK ENFORCEMENT - Network Independent
            enforceKioskModeIndependently()
            
            Log.d(TAG, "✅ PolicyCheckWorker completed successfully")
            return Result.success()

        } catch (e: Exception) {
            Log.e(TAG, "❌ PolicyCheckWorker failed: ${e.message}")
            return Result.retry()
        }
    }

    /**
     * 🚀 HTTP POLLING FALLBACK
     * Fetches the latest device state from the server.
     * This ensures the device is locked even if FCM messages are blocked by OEM deep sleep.
     */
    private suspend fun fetchPoliciesFromServer(policyProcessor: PolicyChangeProcessor) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            val userPrefs = applicationContext.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
            val imei = userPrefs.getString("user_imei1", null) ?: userPrefs.getString("user_imei", "")
            
            if (imei.isNullOrEmpty()) {
                Log.w(TAG, "⚠️ No IMEI found - skipping server poll")
                return@withContext
            }

            Log.d(TAG, "📡 Polling server for state... (IMEI: $imei)")
            
            val client = okhttp3.OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build()

            val payload = org.json.JSONObject().apply {
                put("imei", imei)
            }

            val mediaType = "application/json".toMediaTypeOrNull()
            val requestBody = payload.toString().toRequestBody(mediaType)

            val request = okhttp3.Request.Builder()
                .url(com.renew.jss.ApiConfig.Api.LOCKED_DEVICE_DESC)
                .post(requestBody)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "❌ Server poll failed: ${response.code}")
                    return@withContext
                }

                val body = response.body?.string() ?: ""
                val jsonResponse = org.json.JSONObject(body)
                
                if (jsonResponse.optString("status") == "success") {
                    Log.d(TAG, "✅ Server state fetched successfully")
                    
                    // 🛡️ SECURITY FIX: Do NOT force lock by default!
                    // Only lock if the server response explicitly contains lock instructions.
                    if (jsonResponse.has("lock_device") || jsonResponse.has("policy_data")) {
                        val payload = jsonResponse.optJSONObject("policy_data") ?: jsonResponse
                        policyProcessor.processPolicyPayload(payload, "polling_sync")
                    } else {
                        Log.d(TAG, "📋 Server returned success but no policy instructions - skipping lock")
                    }
                } else {
                    Log.d(TAG, "🔓 Server state: Unlocked (or not found)")
                    // If the device should be unlocked, we handle it if needed
                    // But usually unlock happens via live command.
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to fetch policies from server: ${e.message}")
        }
    }
    
    /**
     * 🔒 NETWORK-INDEPENDENT KIOSK ENFORCEMENT
     * Ensures kiosk mode remains active regardless of network connectivity
     */
    private fun enforceKioskModeIndependently() {
        try {
            // 🔍 DUAL STATE CHECK - More robust verification
            val isLocked = LockedStateStore.isLocked(applicationContext)
            val isKioskEnabled = com.renew.jss.storage.KioskStateManager.isKioskEnabled(applicationContext)
            
            Log.d(TAG, "🔍 Worker state check - Locked: $isLocked, Kiosk: $isKioskEnabled")
            
            // 🔒 ENHANCED: Enforce if EITHER state indicates lock
            if (isLocked || isKioskEnabled) {
                Log.d(TAG, "🔒 Device should be locked - enforcing kiosk mode independently")
                
                // 🔧 SYNC: Ensure both states are consistent
                if (isLocked && !isKioskEnabled) {
                    com.renew.jss.storage.KioskStateManager.setKioskEnabled(applicationContext, true)
                    Log.d(TAG, "🔄 Worker synced: Enabled kiosk state")
                } else if (!isLocked && isKioskEnabled) {
                    LockedStateStore.setLocked(applicationContext, true)
                    Log.d(TAG, "🔄 Worker synced: Enabled lock state")
                }
                
                // 🔒 AGGRESSIVE: Force kiosk policy enforcement
                try {
                    com.renew.jss.policy.KioskPolicy.enforceKioskState(applicationContext)
                    Log.d(TAG, "✅ Worker KioskPolicy enforcement completed")
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Worker KioskPolicy enforcement failed: ${e.message}")
                }
                
                // Start kiosk enforcement service for continuous monitoring
                try {
                    val enforcementIntent = Intent(applicationContext, KioskEnforcementService::class.java)
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        applicationContext.startForegroundService(enforcementIntent)
                    } else {
                        applicationContext.startService(enforcementIntent)
                    }
                    Log.d(TAG, "✅ KioskEnforcementService started for continuous monitoring")
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Failed to start KioskEnforcementService: ${e.message}")
                }
                
                // Ensure kiosk activity is running
                try {
                    val kioskIntent = Intent(applicationContext, com.renew.jss.activity.KioskActivity::class.java)
                    kioskIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    kioskIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    applicationContext.startActivity(kioskIntent)
                    Log.d(TAG, "✅ KioskActivity ensured to be running")
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Failed to start KioskActivity: ${e.message}")
                }
                
                // Record enforcement for debugging
                com.renew.jss.storage.KioskStateManager.recordEnforcement(applicationContext)
                
            } else {
                Log.d(TAG, "🔓 Device is not locked - skipping kiosk enforcement")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to enforce kiosk mode independently: ${e.message}", e)
        }
    }

    /**
     * 🚀 RESTART CORE SERVICES
     * Ensures all background services are running.
     */
    private fun restartCoreServices() {
        try {
            Log.d(TAG, "🚀 Auditing and restarting core services...")
            
            val services = listOf(
                PolicyMonitoringService::class.java,
                AlwaysAliveService::class.java,
                PreventiveService::class.java
            )

            for (serviceClass in services) {
                val intent = Intent(applicationContext, serviceClass)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    applicationContext.startForegroundService(intent)
                } else {
                    applicationContext.startService(intent)
                }
            }
            Log.d(TAG, "✅ Core services restart signal sent")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to restart core services: ${e.message}")
        }
    }

    private fun auditHardeningStatus() {
        try {
            val dpm = applicationContext.getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
            val admin = ComponentName(applicationContext, com.renew.jss.DeviceOwnerReceiver::class.java)
            
            val isAdminActive = dpm.isAdminActive(admin)
            val isAccessibilityEnabled = isAccessibilityServiceEnabled(applicationContext)
            
            Log.d(TAG, "🛡️ Hardening Audit - Admin: $isAdminActive, Accessibility: $isAccessibilityEnabled")
            
            if (isAdminActive) {
                // Device Admin is active
                Log.d(TAG, "✅ Device Admin is active")
            }
            
            if (!isAdminActive || !isAccessibilityEnabled) {
                Log.w(TAG, "🚨 CRITICAL: Hardening missing! Enforce check skipped to prevent interference.")
                /*
                val intent = Intent(applicationContext, com.renew.jss.activity.PermissionEnforcementActivity::class.java)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                applicationContext.startActivity(intent)
                */
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Hardening audit failed: ${e.message}")
        }
    }

    private fun isAccessibilityServiceEnabled(context: Context): Boolean {
        try {
            // 🛡️ DUAL CHECK: Check Settings.Secure + AccessibilityManager
            val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? android.view.accessibility.AccessibilityManager
            val isServiceRunning = am?.isEnabled == true && 
                am.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_GENERIC)
                  ?.any { it.resolveInfo.serviceInfo.packageName == context.packageName } == true
            
            if (isServiceRunning) return true

            val expectedService = ComponentName(context, com.renew.jss.service.MyAccessibilityService::class.java).flattenToString()
            val enabledServices = android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            return enabledServices != null && (enabledServices.contains(expectedService) || enabledServices.contains(context.packageName))
        } catch (e: Exception) {
            return false
        }
    }
}
