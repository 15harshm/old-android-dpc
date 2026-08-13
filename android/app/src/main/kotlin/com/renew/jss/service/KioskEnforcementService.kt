package com.renew.jss.service



import android.app.*

import android.content.Context

import android.content.Intent

import android.os.Build

import android.os.Handler
import android.os.HandlerThread

import android.os.IBinder

import android.os.Looper

import android.util.Log

import androidx.core.app.NotificationCompat

import com.renew.jss.policy.PolicyDispatcher

import com.renew.jss.storage.LockedStateStore

import com.renew.jss.storage.KioskStateManager

import com.renew.jss.activity.KioskActivity

import android.app.ActivityManager



/**

 * 🛡️ KIOSK ENFORCEMENT SERVICE - Robust Lock Persistence

 * 

 * Continuously monitors and enforces kiosk mode regardless of network connectivity.

 * Ensures device remains locked even when WiFi disconnects or services restart.

 */

class KioskEnforcementService : Service() {



    companion object {

        private const val TAG = "KioskEnforcementService"

        private const val NOTIFICATION_ID = 1004

        private const val CHANNEL_ID = "protection_channel_v2"

        private const val ENFORCEMENT_INTERVAL_MS = 5000L // ✅ ANR FIX: 5s (was 3s) — reduces KioskActivity launch rate and Binder pressure

        

        @Volatile

        var isRunning = false

    }



    private var handler: Handler? = null
    private var handlerThread: HandlerThread? = null
    private var enforcementRunnable: Runnable? = null

    private var activityManager: ActivityManager? = null



    override fun onCreate() {

        super.onCreate()

        Log.d(TAG, "🛡️ KioskEnforcementService onCreate()")

        

        createNotificationChannel()
        
        handlerThread = HandlerThread("KioskEnforcementThread")
        handlerThread?.start()
        handler = Handler(handlerThread!!.looper)

        activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

        

        isRunning = true

    }



    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {

        Log.d(TAG, "🛡️ KioskEnforcementService onStartCommand()")

        

        // Start foreground service

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, buildNotification(), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {

            startForeground(NOTIFICATION_ID, buildNotification())

        }

        

        // Start continuous enforcement

        startEnforcement()

        

        return START_STICKY

    }



    override fun onBind(intent: Intent?): IBinder? = null



    override fun onDestroy() {

        super.onDestroy()

        Log.d(TAG, "🛡️ KioskEnforcementService onDestroy()")

        

        // Stop enforcement
        stopEnforcement()
        
        handlerThread?.quitSafely()

        

        isRunning = false

        

        // Auto-restart service

        restartService()

    }



    /**

     * 🔄 START CONTINUOUS ENFORCEMENT

     */

    private fun startEnforcement() {

        enforcementRunnable = object : Runnable {

            override fun run() {

                try {

                    enforceKioskState()

                } catch (e: Exception) {

                    Log.e(TAG, "❌ Error during kiosk enforcement: ${e.message}", e)

                }

                

                // Schedule next enforcement check

                handler?.postDelayed(this, ENFORCEMENT_INTERVAL_MS)

            }

        }

        

        handler?.post(enforcementRunnable!!)

        Log.d(TAG, "🔄 Started continuous kiosk enforcement (every ${ENFORCEMENT_INTERVAL_MS}ms)")

    }



    /**

     * 🛑 STOP CONTINUOUS ENFORCEMENT

     */

    private fun stopEnforcement() {

        enforcementRunnable?.let { runnable ->

            handler?.removeCallbacks(runnable)

        }

        enforcementRunnable = null

        Log.d(TAG, "🛑 Stopped continuous kiosk enforcement")

    }



    /**

     * 🔒 ENFORCE KIOSK STATE

     */

    private fun enforceKioskState() {

        try {

            // 🔍 DUAL STATE CHECK - More robust verification

            val shouldBeLocked = LockedStateStore.isLocked(this)

            val shouldBeKiosk = KioskStateManager.isKioskEnabled(this)

            

            Log.d(TAG, "🔍 State check - Locked: $shouldBeLocked, Kiosk: $shouldBeKiosk")

            

            // 🔒 ENHANCED: Enforce if EITHER state indicates lock

            if (shouldBeLocked || shouldBeKiosk) {

                Log.d(TAG, "🔒 Device should be locked - enforcing kiosk mode")

                

                // 🔧 SYNC: Ensure both states are consistent

                if (shouldBeLocked && !shouldBeKiosk) {

                    KioskStateManager.setKioskEnabled(this, true)

                    Log.d(TAG, "🔄 Synced: Enabled kiosk state")

                } else if (!shouldBeLocked && shouldBeKiosk) {

                    LockedStateStore.setLocked(this, true)

                    Log.d(TAG, "🔄 Synced: Enabled lock state")

                }

                

                // 🔒 AGGRESSIVE: Re-apply kiosk policies every time

// Policy applied via KioskPolicy.enforceKioskState below

                

                // 🔒 AGGRESSIVE: Force kiosk policy enforcement

                try {

                    com.renew.jss.policy.KioskPolicy.enforceKioskState(this)

                    Log.d(TAG, "✅ KioskPolicy enforcement completed")

                } catch (e: Exception) {

                    Log.e(TAG, "❌ KioskPolicy enforcement failed: ${e.message}")

                }

                

                // Ensure kiosk activity is running

                ensureKioskActivityRunning()

                

                // Verify system settings

                verifyKioskSystemSettings()

                

                // Record enforcement for debugging

                KioskStateManager.recordEnforcement(this)

                

            } else {

                Log.d(TAG, "🔓 Device should not be locked - skipping enforcement")

            }

            

        } catch (e: Exception) {

            Log.e(TAG, "❌ Failed to enforce kiosk state: ${e.message}", e)

        }

    }



    /**

     * 📱 ENSURE KIOSK ACTIVITY IS RUNNING

     */

    private fun ensureKioskActivityRunning() {

        try {

            val isKioskActivityRunning = isActivityRunning(KioskActivity::class.java)

            

            if (!isKioskActivityRunning) {

                Log.w(TAG, "⚠️ KioskActivity is not running - restarting it")

                

                // Start kiosk activity with SINGLE_TOP to reuse the existing instance
                // FLAG_ACTIVITY_NO_ANIMATION prevents any visual flash/transition
                val intent = Intent(this, KioskActivity::class.java)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
                startActivity(intent)

                

                Log.d(TAG, "✅ KioskActivity restarted")

            } else {

                Log.d(TAG, "✅ KioskActivity is already running")

            }

            

        } catch (e: Exception) {

            Log.e(TAG, "❌ Failed to ensure KioskActivity is running: ${e.message}", e)

        }

    }



    /**

     * 🔧 VERIFY KIOSK SYSTEM SETTINGS

     */

    private fun verifyKioskSystemSettings() {

        try {

            // This would involve checking Device Policy Manager settings

            // For now, we'll just log that verification is happening

            Log.d(TAG, "🔧 Verifying kiosk system settings")

            

            // You could add checks for:

            // - Lock task packages are still set

            // - Status bar is disabled

            // - Keyguard is disabled

            // - Other device policy settings

            

        } catch (e: Exception) {

            Log.e(TAG, "❌ Failed to verify kiosk system settings: ${e.message}", e)

        }

    }



    /**

     * 📊 CHECK IF ACTIVITY IS RUNNING

     */

    private fun isActivityRunning(activityClass: Class<*>): Boolean {

        return try {

            activityManager?.getRunningTasks(Integer.MAX_VALUE)?.any { task ->

                task.topActivity?.className == activityClass.name

            } ?: false

        } catch (e: Exception) {

            Log.e(TAG, "❌ Error checking if activity is running: ${e.message}", e)

            false

        }

    }



    /**

     * 🔄 IMMEDIATE RESTART

     */

    private fun restartService() {

        try {

            val restartIntent = Intent(this, KioskEnforcementService::class.java)

            startForegroundService(restartIntent)

            Log.d(TAG, "🔄 Service restart initiated")

        } catch (e: Exception) {

            Log.e(TAG, "❌ Failed to restart service: ${e.message}")

        }

    }



    /**

     * 🔔 BUILD NOTIFICATION

     */

    private fun buildNotification(): Notification {

        return NotificationCompat.Builder(this, CHANNEL_ID)

            .setSmallIcon(android.R.drawable.ic_lock_lock)

            .setContentTitle("${com.renew.jss.ApiConfig.GENERIC_APP_NAME} System")

            .setContentText("Device protection is active")

            .setOngoing(true)

            .setCategory(NotificationCompat.CATEGORY_SERVICE)

            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

            .setPriority(NotificationCompat.PRIORITY_DEFAULT)

            .setOnlyAlertOnce(true)

            .setSilent(true)

            .build()

    }



    /**

     * 📢 CREATE NOTIFICATION CHANNEL

     */

    private fun createNotificationChannel() {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

            val channel = NotificationChannel(

                CHANNEL_ID,

                "${com.renew.jss.ApiConfig.GENERIC_APP_NAME} System",

                NotificationManager.IMPORTANCE_DEFAULT

            ).apply {

                description = "Device protection system"

                setShowBadge(false)

                lockscreenVisibility = Notification.VISIBILITY_PUBLIC

            }

            

            getSystemService(NotificationManager::class.java)

                .createNotificationChannel(channel)

        }
    }
}

