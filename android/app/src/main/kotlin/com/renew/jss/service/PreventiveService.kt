package com.renew.jss.service

import android.app.*
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.app.PendingIntent
import android.app.AlarmManager
import androidx.core.app.NotificationCompat
import java.util.*
import com.renew.jss.ApiConfig

class PreventiveService : Service() {

    private var timer: Timer? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            startForeground(1003, buildNotification(), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1003, buildNotification())
        }
        startWatchdog()
    }

    private fun startWatchdog() {
        timer?.cancel()
        timer = Timer()

        timer?.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                reviveServices()
            }
        }, 1000, 3_600_000) // every 1 hour
    }

    private fun reviveServices() {
        // Opt #7: startForegroundService() is thread-safe — no need to hop to main thread.
        try {
            startForegroundService(Intent(this, PolicyMonitoringService::class.java))
            startForegroundService(Intent(this, AlwaysAliveService::class.java))
            // ⚠️ Intentionally NOT restarting PreventiveService here to prevent double-instance accumulation
        } catch (e: Exception) {
            // Ignore — system may refuse in certain states
        }
    }

    /**
     * 🔄 COMPETITOR-COMPATIBLE AUTO-RESTART
     */
    override fun onDestroy() {
        super.onDestroy()
        
        // 🔴 EXACT COMPETITOR BEHAVIOR - Like appBrhino
        restartService()
        scheduleAlarmManagerRestart()
        
        timer?.cancel()
    }

    /**
     * 📱 TASK REMOVED HANDLER - Like appBrhino competitor
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        
        // 🔴 EXACT COMPETITOR BEHAVIOR - Like appBrhino
        restartService()
        scheduleAlarmManagerRestart()
    }

    /**
     * 🔄 IMMEDIATE RESTART
     */
    private fun restartService() {
        try {
            val restartIntent = Intent(this, PreventiveService::class.java)
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
            val restartIntent = Intent(this, PreventiveService::class.java)
            val pendingIntent = PendingIntent.getService(
                this, 
                3, 
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

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




