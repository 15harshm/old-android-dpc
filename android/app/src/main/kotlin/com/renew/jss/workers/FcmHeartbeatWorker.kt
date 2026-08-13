package com.renew.jss.workers

import android.content.Context
import android.util.Log
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.renew.jss.service.PolicyMonitoringService
import com.renew.jss.DeviceOwnerReceiver
import java.util.concurrent.TimeUnit

/**
 * 🚀 FCM Heartbeat Worker - Like competitor CommandExecuteWorker
 * 
 * Purpose:
 * 1. Keep EMI Locker responsive when idle
 * 2. Ensure FCM services stay active
 * 3. Periodic check-in with backend
 * 4. Auto-restart if services killed
 */
class FcmHeartbeatWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val TAG = "FCMPC_FcmHeartbeatWorker"
        const val WORK_NAME = "fcm_heartbeat_work"
        
        /**
         * 🚀 Start periodic heartbeat like competitor
         */
        fun startHeartbeat(context: Context) {
            try {
                // Check if app is device admin and setup is completed (ADMIN PERMISSIONS ONLY)
                val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                val admin = ComponentName(context, DeviceOwnerReceiver::class.java)
                
                if (dpm.isAdminActive(admin)) {
                    val prefs = context.getSharedPreferences("device_admin_setup", Context.MODE_PRIVATE)
                    val isSetupCompleted = prefs.getBoolean("setup_completed", false)
                    
                    if (isSetupCompleted) {
                        Log.d(TAG, "✅ Device admin setup completed - starting FCM heartbeat")
                        
                        val constraints = Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build()
                        
                        val heartbeatRequest = PeriodicWorkRequest.Builder(
                            FcmHeartbeatWorker::class.java,
                            15, // Every 15 minutes (more frequent than competitor's 30 min)
                            TimeUnit.MINUTES
                        )
                        .setInitialDelay(5, TimeUnit.MINUTES) // Start after 5 minutes
                        .setConstraints(constraints)
                        .build()
                        
                        WorkManager.getInstance(context)
                            .enqueueUniquePeriodicWork(
                                WORK_NAME,
                                ExistingPeriodicWorkPolicy.KEEP,
                                heartbeatRequest
                            )
                        
                        Log.d(TAG, "✅ FCM Heartbeat worker started successfully")
                    } else {
                        Log.d(TAG, "⏭️ Device admin setup not completed - skipping FCM heartbeat start")
                    }
                } else {
                    Log.d(TAG, "⏭️ App is not device admin - skipping FCM heartbeat start")
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to start FCM heartbeat worker: ${e.message}")
            }
        }
        
        /**
         * 🛑 Stop heartbeat worker
         */
        fun stopHeartbeat(context: Context) {
            try {
                WorkManager.getInstance(context)
                    .cancelUniqueWork(WORK_NAME)
                Log.d(TAG, "🛑 FCM Heartbeat worker stopped")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to stop FCM heartbeat worker: ${e.message}")
            }
        }
    }

    override suspend fun doWork(): Result {
        return try {
            Log.d(TAG, "🚀 FCM Heartbeat check - Keeping services alive")
            
            // ✅ Ensure PolicyMonitoringService is running
            if (!PolicyMonitoringService.isRunning) {
                Log.d(TAG, "🔄 PolicyMonitoringService not running - restarting")
                val intent = android.content.Intent(context, PolicyMonitoringService::class.java)
                context.startForegroundService(intent)
            }
            
            // ✅ Check FCM token registration
            val prefs = context.getSharedPreferences("fcm_prefs", Context.MODE_PRIVATE)
            val fcmToken = prefs.getString("fcm_token", null)
            
            if (fcmToken == null) {
                Log.w(TAG, "⚠️ No FCM token found - may need to re-register")
                // Trigger FCM token refresh
                val intent = android.content.Intent("com.google.firebase.INSTANCE_ID_EVENT")
                context.sendBroadcast(intent)
            } else {
                Log.d(TAG, "✅ FCM token present: ${fcmToken.take(20)}...")
            }
            
            // ✅ Heartbeat success
            Log.d(TAG, "✅ FCM Heartbeat completed successfully")
            Result.success()
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ FCM Heartbeat failed: ${e.message}")
            Result.retry() // Retry on failure
        }
    }
}
