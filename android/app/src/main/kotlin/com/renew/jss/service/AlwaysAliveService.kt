package com.renew.jss.service

import android.app.*
import android.content.Intent
import android.content.IntentFilter
import android.content.Context
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import com.renew.jss.ApiConfig
import com.renew.jss.receivers.ScreenActionReceiver

class AlwaysAliveService : Service() {

    private var screenReceiver: ScreenActionReceiver? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            startForeground(1002, buildNotification(), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1002, buildNotification())
        }

        // 🔄 Register Screen Action Receiver for persistence
        // Note: Only USER_PRESENT is registered here — SCREEN_ON fires during the
        // unlock animation and starting services then causes a black flash.
        try {
            screenReceiver = ScreenActionReceiver()
            val filter = IntentFilter()
            // REMOVED ACTION_SCREEN_ON: fires mid-animation → causes black flash on unlock
            // REMOVED ACTION_SCREEN_OFF: no value in restarting services when screen is off
            filter.addAction(Intent.ACTION_USER_PRESENT) // fires AFTER unlock animation completes
            registerReceiver(screenReceiver, filter)
            Log.d("AlwaysAliveService", "✅ ScreenActionReceiver registered (USER_PRESENT only)")
        } catch (e: Exception) {
            Log.e("AlwaysAliveService", "❌ Failed to register screen receiver: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * 🔄 COMPETITOR-COMPATIBLE AUTO-RESTART
     */
    override fun onDestroy() {
        super.onDestroy()
        
        // 🔄 Unregister screen receiver
        try {
            screenReceiver?.let { unregisterReceiver(it) }
        } catch (e: Exception) {
            // Ignore
        }
        
        // 🚨 CRITICAL: Auto-restart like appBrhino competitor
        restartService()
        scheduleAlarmManagerRestart()
    }

    /**
     * 📱 TASK REMOVED HANDLER - Like appBrhino competitor
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        
        // Restart service immediately when app is swiped
        restartService()
        scheduleAlarmManagerRestart()
    }

    /**
     * 🔄 IMMEDIATE RESTART
     */
    private fun restartService() {
        try {
            val restartIntent = Intent(this, AlwaysAliveService::class.java)
            startForegroundService(restartIntent)
        } catch (e: Exception) {
            // Log error but continue
        }
    }

    /**
     * ⏰ ALARM MANAGER BACKUP
     */
    private fun scheduleAlarmManagerRestart() {
        try {
            val alarmManager = getSystemService(ALARM_SERVICE) as AlarmManager
            val restartIntent = Intent(this, AlwaysAliveService::class.java)
            val pendingIntent = PendingIntent.getService(
                this, 
                2, 
                restartIntent, 
                PendingIntent.FLAG_IMMUTABLE
            )
            
            alarmManager.set(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + 1000,
                pendingIntent
            )
        } catch (e: Exception) {
            // Log error but continue
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, "protection_channel_v2")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("${ApiConfig.GENERIC_APP_NAME} System")
            .setContentText("Device protection is active")
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                "protection_channel_v2",
                "${ApiConfig.GENERIC_APP_NAME} System",
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
