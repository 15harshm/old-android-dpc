package com.renew.jss.utils

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.renew.jss.service.PolicyMonitoringService

object NotificationHelper {
    
    private const val EMI_REMINDER_CHANNEL_ID = "emi_reminder_channel"
    private const val EMI_REMINDER_NOTIFICATION_ID = 2001
    
    fun createEmiReminderChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                EMI_REMINDER_CHANNEL_ID,
                "EMI Reminders",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Payment reminders for your EMIs"
                setShowBadge(true)
                enableVibration(true)
                enableLights(true)
            }
            
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    fun showEmiReminder(
        context: Context,
        reminderText: String,
        shopName: String? = null,
        nextEmiDate: String? = null,
        reminderLanguage: String? = null
    ) {
        // Create notification channel if needed
        createEmiReminderChannel(context)

        // NOTE: audio playback is intentionally NOT triggered here. The caller
        // (PolicyMonitoringService) plays the reminder via TTSHelper directly, so
        // speaking here too would double-fire the audio and QUEUE_FLUSH would cut
        // off the caller's (bilingual) reminder. This method only shows the notification.

        // Create notification intent (optional - for user interaction)
        val intent = Intent(context, PolicyMonitoringService::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE
        )
        
        // Build notification
        val notificationBuilder = NotificationCompat.Builder(context, EMI_REMINDER_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("EMI Reminder")
            .setContentText(reminderText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(reminderText))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
        
        // Add shop name if available
        shopName?.let {
            notificationBuilder.setSubText("From: $it")
        }
        
        // Show notification
        try {
            val notificationManager = NotificationManagerCompat.from(context)
            notificationManager.notify(EMI_REMINDER_NOTIFICATION_ID, notificationBuilder.build())
            Log.d("NotificationHelper", "EMI reminder notification shown")
        } catch (e: SecurityException) {
            Log.e("NotificationHelper", "Failed to show notification: ${e.message}")
        }
    }
}


