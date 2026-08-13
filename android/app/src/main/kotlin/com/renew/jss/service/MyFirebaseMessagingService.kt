package com.renew.jss.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.telephony.TelephonyManager
import android.util.Log
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import com.renew.jss.ApiConfig
import org.json.JSONObject

/**
 * 🚀 FCM MESSAGING SERVICE - For Policy Application & Notifications
 * 
 * Purpose: 
 * 1. Handle policy updates from backend (like competitor)
 * 2. Show notifications when received from backend
 * 3. Process commands directly without socket dependency
 */
class MyFirebaseMessagingService : FirebaseMessagingService() {

    companion object {
        private const val TAG = "FCMPC_FCM_SocketService"
        private val CHANNEL_ID = "${ApiConfig.APP_NAME} notifications"
        private val CHANNEL_NAME = "${ApiConfig.GENERIC_APP_NAME} Notifications"
        private val CHANNEL_DESCRIPTION = "Important notifications from ${ApiConfig.GENERIC_APP_NAME}"
        private const val PREFS_NAME = "fcm_message_tracker"
        private const val KEY_PROCESSED_MESSAGES = "processed_messages"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        Log.d(TAG, "FCMPC 🚀 FCM: Message received from: ${remoteMessage.from}")
        Log.d(TAG, "FCMPC 🚀 FCM: Message ID: ${remoteMessage.messageId}")
        
        // 🎯 CRITICAL FIX: Acknowledge message immediately to prevent re-delivery
        try {
            // Send acknowledgment to Firebase
            remoteMessage.messageId?.let { messageId ->
                Log.d(TAG, "FCMPC 🚀 FCM: Acknowledging message: $messageId")
                // Firebase automatically acknowledges when onMessageReceived completes successfully
            }
        } catch (e: Exception) {
            Log.e(TAG, "FCMPC ❌ Failed to acknowledge FCM message: ${e.message}")
        }
        
        // Check if message contains data payload
        if (remoteMessage.data.isNotEmpty()) {
            Log.d(TAG, "FCMPC 🚀 FCM: Message data payload: ${remoteMessage.data}")
            
            // 🎯 CRITICAL FIX: Check if we've already processed this message
            val messageId = remoteMessage.messageId ?: return
            if (isMessageAlreadyProcessed(messageId)) {
                Log.w(TAG, "FCMPC ⏭️ Message $messageId already processed - skipping")
                return
            }
            
            // Mark message as processed
            markMessageAsProcessed(messageId)
            
            // � NEW: Check for payload field (backend format)
            val hasPayload = remoteMessage.data.containsKey("payload")
            val hasType = remoteMessage.data.containsKey("type")
            
            when {
                hasPayload -> {
                    // Backend sends: { imei, payload: JSON.stringify(data) }
                    Log.d(TAG, "FCMPC 🚀 FCM: Detected payload field - treating as POLICY_UPDATE")
                    handlePolicyUpdateCommand(remoteMessage.data)
                }
                hasType -> {
                    // Standard format: { type: "POLICY_UPDATE", ... }
                    val messageType = remoteMessage.data["type"]
                    when (messageType) {
                        "POLICY_UPDATE" -> {
                            Log.d(TAG, "FCMPC 🚀 FCM: POLICY_UPDATE command received")
                            handlePolicyUpdateCommand(remoteMessage.data)
                        }
                        "FETCH_GPS" -> {
                            Log.d(TAG, "FCMPC soc-loc 🚀 FCM: FETCH_GPS command received")
                            handleFetchGpsCommand()
                        }
                        "AUDIO_REMINDER" -> {
                            Log.d(TAG, "FCMPC 🔊 FCM: AUDIO_REMINDER command received")
                            handleAudioReminderCommand(remoteMessage.data)
                        }
                        "REMOVE_MOBILE" -> {
                            Log.d(TAG, "FCMPC 📵 FCM: REMOVE_MOBILE command received")
                            handleRemoveMobileCommand()
                        }
                        "DISABLE_ACCESSIBILITY" -> {
                            Log.d(TAG, "FCMPC ♿ FCM: DISABLE_ACCESSIBILITY command received")
                            handleDisableAccessibilityCommand()
                        }
                        "NOTIFICATION" -> {
                            Log.d(TAG, "FCMPC 📶 FCM: NOTIFICATION (online heartbeat) command received")
                            handleNotificationCommand(remoteMessage.data)
                        }
                        else -> {
                            Log.d(TAG, "FCMPC 🚀 FCM: Unknown message type: $messageType")
                            Log.d(TAG, "FCMPC 🚀 FCM: Ignoring unknown message type")
                        }
                    }
                }
                else -> {
                    // Legacy: No payload or type field - ignore
                    Log.d(TAG, "FCMPC 🚀 FCM: No payload or type field - ignoring message")
                }
            }
        }

        // Check if message contains notification payload and show it
        remoteMessage.notification?.let { notification ->
            Log.d(TAG, "FCMPC ?? FCM: Message Notification Title: ${notification.title}")
            Log.d(TAG, "FCMPC ?? FCM: Message Notification Body: ${notification.body}")
            
            // Show the notification
            showNotification(
                title = notification.title ?: ApiConfig.GENERIC_APP_NAME,
                message = notification.body ?: "New notification received"
            )
        }
        
        // Also check for notification in data payload (fallback)
        if (remoteMessage.notification == null && remoteMessage.data.isNotEmpty()) {
            val title = remoteMessage.data["title"]
            val message = remoteMessage.data["message"]
            
            if (title != null && message != null) {
                Log.d(TAG, "FCMPC ?? FCM: Data payload notification - Title: $title, Message: $message")
                showNotification(title, message)
            }
        }
    }

    override fun onNewToken(token: String) {
        Log.d(TAG, "FCMPC 🚀 FCM: New token received")
        Log.d(TAG, "FCMPC 🚀 FCM: Token length: ${token.length}")
        Log.d(TAG, "FCMPC 🚀 FCM: Token preview: ${token.take(20)}...")
        
        // Save token locally
        saveTokenLocally(token)
        
        // Check if device admin setup is completed before sending to backend
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(this, com.renew.jss.DeviceOwnerReceiver::class.java)
        
        if (dpm.isAdminActive(admin)) {
            val prefs = getSharedPreferences("device_admin_setup", Context.MODE_PRIVATE)
            val isSetupCompleted = prefs.getBoolean("setup_completed", false)
            
            if (isSetupCompleted) {
                Log.d(TAG, "FCMPC ✅ Device owner setup completed - sending FCM token to backend")
                sendTokenToBackend(token)
            } else {
                Log.d(TAG, "FCMPC ⏭️ Device owner setup not completed - FCM token saved locally, will send after setup")
            }
        } else {
            Log.d(TAG, "FCMPC ⏭️ App is not device owner - FCM token saved locally, will send if device owner is granted")
        }
    }

    /**
     * ?? Create notification channel (required for Android 8.0+)
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = CHANNEL_DESCRIPTION
                enableLights(true)
                enableVibration(true)
            }

            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
            
            Log.d(TAG, "?? FCM: Notification channel created")
        }
    }

    /**
     * ?? Show notification to user
     */
    private fun showNotification(title: String, message: String) {
        try {
            // Create intent to open main activity when notification is tapped
            val intent = Intent(this, com.renew.jss.activity.MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            
            val pendingIntent = PendingIntent.getActivity(
                this, 
                0, 
                intent, 
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            // Build notification
            val notificationBuilder = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info) // You can customize this
                .setContentTitle(title)
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .setDefaults(NotificationCompat.DEFAULT_ALL)

            // Show notification
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val notificationId = System.currentTimeMillis().toInt() // Unique ID for each notification
            
            notificationManager.notify(notificationId, notificationBuilder.build())
            
            Log.d(TAG, "? FCM: Notification shown successfully - Title: $title")
            
        } catch (e: Exception) {
            Log.e(TAG, "? FCM: Failed to show notification: ${e.message}")
        }
    }

    /**
     * Save FCM token locally
     */
    private fun saveTokenLocally(token: String) {
        try {
            val prefs = getSharedPreferences("fcm_prefs", Context.MODE_PRIVATE)
            prefs.edit().putString("fcm_token", token).apply()
            Log.d(TAG, "?? FCM: Token saved locally")
        } catch (e: Exception) {
            Log.e(TAG, "? FCM: Failed to save token locally: ${e.message}")
        }
    }

    /**
     * Send FCM token to backend
     */
    private fun sendTokenToBackend(token: String) {
        Log.d(TAG, "?? FCM: Sending FCM token to backend...")
        
        try {
            val imei = getDeviceImei()
            if (imei == null) {
                Log.e(TAG, "? FCM: IMEI not available, cannot send token")
                return
            }

            val backendUrl = ApiConfig.FCM_TOKEN_ENDPOINT
            val client = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build()

            val prefs = getSharedPreferences("fcm_prefs", Context.MODE_PRIVATE)
            val isTokenSent = prefs.getBoolean("fcm_token_sent", false)
            
            val json = """{"imei": "$imei", "fcmToken": "$token", "first_time": ${!isTokenSent}}"""
            Log.d(TAG, "?? FCM: JSON payload = $json")
            Log.d(TAG, "?? FCM: Backend URL = $backendUrl")
            
            val request = okhttp3.Request.Builder()
                .url(backendUrl)
                .post(json.toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    Log.e(TAG, "? FCM: Failed to send token to backend: ${e.message}")
                }

                override fun onResponse(call: Call, response: Response) {
                    if (response.isSuccessful) {
                        Log.d(TAG, "? FCM: Token sent to backend successfully")
                        // Mark token as sent successfully
                        getSharedPreferences("fcm_prefs", Context.MODE_PRIVATE)
                            .edit().putBoolean("fcm_token_sent", true).apply()
                            
                        response.body?.string()?.let { responseBody ->
                            Log.d(TAG, "?? FCM: Backend response: $responseBody")
                        }
                    } else {
                        Log.e(TAG, "? FCM: Backend returned error: ${response.code}")
                    }
                    response.close()
                }
            })

        } catch (e: Exception) {
            Log.e(TAG, "? FCM: Exception sending token to backend: ${e.message}")
        }
    }

    /**
     * 🚀 Handle POLICY_UPDATE command from FCM (like competitor)
     */
    private fun handlePolicyUpdateCommand(data: Map<String, String>) {
        Log.d(TAG, "FCMPC 🚀 FCM: Handling POLICY_UPDATE command")
        
        try {
            // Extract policy data from FCM message
            val policyData = JSONObject()
            
            // 🔧 NEW: Parse payload field from backend
            val payloadStr = data["payload"]
            if (!payloadStr.isNullOrEmpty()) {
                Log.d(TAG, "FCMPC 🚀 FCM: Parsing payload field: $payloadStr")
                
                try {
                    val payloadJson = JSONObject(payloadStr)
                    
                    // Extract all policy fields from payload JSON
                    payloadJson.keys().forEach { key ->
                        policyData.put(key, payloadJson.get(key))
                    }
                    
                    Log.d(TAG, "FCMPC 🚀 FCM: Parsed ${payloadJson.length()} fields from payload")
                } catch (e: Exception) {
                    Log.e(TAG, "FCMPC 🚀 FCM: Failed to parse payload JSON: ${e.message}")
                }
            }
            
            // 🔧 LEGACY: Also check individual fields (fallback)
            data["lock_device"]?.let { policyData.put("lock_device", it.toBoolean()) }
            data["camera_enabled"]?.let { policyData.put("camera_enabled", it.toBoolean()) }
            data["wifi_enabled"]?.let { policyData.put("wifi_enabled", it.toBoolean()) }
            data["mobile_data_enabled"]?.let { policyData.put("mobile_data_enabled", it.toBoolean()) }
            data["usb_debugging_enabled"]?.let { policyData.put("usb_debugging_enabled", it.toBoolean()) }
            data["offline_lock"]?.let { policyData.put("offline_lock", it.toBoolean()) }
            data["factory_reset_enabled"]?.let { policyData.put("factory_reset_enabled", it.toBoolean()) }
            data["calls_enabled"]?.let { policyData.put("calls_enabled", it.toBoolean()) }
            data["social_apps_enabled"]?.let { policyData.put("social_apps_enabled", it.toBoolean()) }
            data["get_sim_info"]?.let { policyData.put("get_sim_info", it.toBoolean()) }
            data["get_location"]?.let { policyData.put("get_location", it.toBoolean()) }
            // 👁️ App icon hide/show — true=hidden, false=visible (default)
            // Works with Device Admin only; uses PackageManager.setComponentEnabledSetting()
            // on LauncherAlias. MainActivity and all services remain fully functional.
            data["hide_application"]?.let { policyData.put("hide_application", it.toBoolean()) }
            
            // Extract command ID if present (backend sends "commandId" not "command_id")
            val commandId = data["commandId"] ?: data["command_id"] ?: "0"
            
            Log.d(TAG, "FCMPC 🚀 FCM: Final policy data: $policyData")
            Log.d(TAG, "FCMPC 🚀 FCM: Command ID: $commandId")
            
            // Forward to PolicyMonitoringService for processing (same flow as socket)
            val intent = Intent(this, com.renew.jss.service.PolicyMonitoringService::class.java)
            intent.putExtra("policy_data", policyData.toString())
            intent.putExtra("command_id", commandId)
            intent.putExtra("source", "fcm")
            
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            
            Log.d(TAG, "FCMPC 🚀 FCM: POLICY_UPDATE command forwarded to PolicyMonitoringService")
            
        } catch (e: Exception) {
            Log.e(TAG, "FCMPC 🚀 FCM: Failed to handle POLICY_UPDATE command: ${e.message}")
        }
    }

    /**
     * 🔊 Handle AUDIO_REMINDER command from FCM (like competitor)
     */
    private fun handleAudioReminderCommand(data: Map<String, String>) {
        Log.d(TAG, "FCMPC 🔊 FCM: Handling AUDIO_REMINDER command")
        
        try {
            // Extract audio reminder data
            val reminderText = data["reminder_text"] ?: "EMI payment reminder"
            val reminderLanguage = data["reminder_language"] ?: "en"
            val nextEmiDate = data["next_emi_date"] ?: ""
            val shopName = data["shop_name"] ?: ""
            
            Log.d(TAG, "FCMPC 🔊 FCM: Audio reminder - Text: $reminderText, Language: $reminderLanguage")
            
            // Forward to PolicyMonitoringService for audio processing
            val intent = Intent(this, com.renew.jss.service.PolicyMonitoringService::class.java)
            intent.putExtra("audio_reminder_text", reminderText)
            intent.putExtra("audio_reminder_language", reminderLanguage)
            intent.putExtra("next_emi_date", nextEmiDate)
            intent.putExtra("shop_name", shopName)
            intent.putExtra("source", "fcm")
            
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            
            Log.d(TAG, "FCMPC 🔊 FCM: AUDIO_REMINDER command forwarded to PolicyMonitoringService")
            
        } catch (e: Exception) {
            Log.e(TAG, "FCMPC 🔊 FCM: Failed to handle AUDIO_REMINDER command: ${e.message}")
        }
    }

    /**
     * 🚀 Handle FETCH_GPS command from FCM
     */
    private fun handleFetchGpsCommand() {
        Log.d(TAG, "FCMPC soc-loc 🚀 FCM: Handling FETCH_GPS command")
        
        try {
            // Start PolicyMonitoringService to handle GPS fetch
            val intent = Intent(this, com.renew.jss.service.PolicyMonitoringService::class.java)
            intent.putExtra("fetch_gps_command", true)
            
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            
            Log.d(TAG, "FCMPC soc-loc 🚀 FCM: FETCH_GPS command forwarded to PolicyMonitoringService")
        } catch (e: Exception) {
            Log.e(TAG, "FCMPC soc-loc 🚀 FCM: Failed to handle FETCH_GPS command: ${e.message}")
        }
    }

    /**
     * 📵 Handle REMOVE_MOBILE command from FCM
     */
    private fun handleRemoveMobileCommand() {
        Log.d(TAG, "FCMPC 📵 FCM: Handling REMOVE_MOBILE command")
        
        try {
            // Start PolicyMonitoringService to handle remove mobile restrictions
            val intent = Intent(this, com.renew.jss.service.PolicyMonitoringService::class.java)
            intent.putExtra("remove_mobile_command", true)
            
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            
            Log.d(TAG, "FCMPC 📵 FCM: REMOVE_MOBILE command forwarded to PolicyMonitoringService")
        } catch (e: Exception) {
            Log.e(TAG, "FCMPC 📵 FCM: Failed to handle REMOVE_MOBILE command: ${e.message}")
        }
    }

    /**
     * ♿ Handle DISABLE_ACCESSIBILITY command from FCM
     */
    private fun handleDisableAccessibilityCommand() {
        Log.d(TAG, "FCMPC ♿ FCM: Handling DISABLE_ACCESSIBILITY command")
        
        try {
            val intent = Intent(this, com.renew.jss.service.PolicyMonitoringService::class.java)
            intent.putExtra("disable_accessibility_command", true)
            
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            
            Log.d(TAG, "FCMPC ♿ FCM: DISABLE_ACCESSIBILITY command forwarded to PolicyMonitoringService")
        } catch (e: Exception) {
            Log.e(TAG, "FCMPC ♿ FCM: Failed to handle DISABLE_ACCESSIBILITY command: ${e.message}")
        }
    }

    /**
     * 📶 Handle NOTIFICATION command from FCM.
     *
     * Acts as an online "heartbeat"/ping: the backend sends a (usually silent) NOTIFICATION
     * push to check whether a device is reachable. On receipt we resolve the device IMEI
     * (from the payload, or the locally saved IMEI) and report active/online status to the
     * backend via FCM_DeviceActiveStatus.php.
     */
    private fun handleNotificationCommand(data: Map<String, String>) {
        Log.d(TAG, "FCMPC 📶 FCM: Handling NOTIFICATION (online heartbeat) command")

        try {
            // Prefer the IMEI supplied in the payload; fall back to the saved device IMEI.
            val imei = data["imei"]?.takeIf { it.isNotBlank() } ?: getDeviceImei()

            if (imei.isNullOrBlank()) {
                Log.e(TAG, "FCMPC ❌ FCM: NOTIFICATION - IMEI not available, cannot report active status")
                return
            }

            sendDeviceActiveStatus(imei, 1)
        } catch (e: Exception) {
            Log.e(TAG, "FCMPC ❌ FCM: Failed to handle NOTIFICATION command: ${e.message}")
        }
    }

    /**
     * 📶 Report device active/online status to the backend (FCM_DeviceActiveStatus.php).
     *
     * POSTs: {"action":"addImeiStatus","imei":"<imei>","status":<status>}  (status 1 = online)
     */
    private fun sendDeviceActiveStatus(imei: String, status: Int) {
        try {
            val url = ApiConfig.DEVICE_ACTIVE_STATUS

            val json = JSONObject().apply {
                put("action", "addImeiStatus")
                put("imei", imei)
                put("status", status)
            }.toString()

            Log.d(TAG, "FCMPC 📶 FCM: Reporting active status → URL=$url payload=$json")

            val client = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build()

            val request = okhttp3.Request.Builder()
                .url(url)
                .post(json.toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    Log.e(TAG, "FCMPC ❌ FCM: Failed to report active status: ${e.message}")
                }

                override fun onResponse(call: Call, response: Response) {
                    if (response.isSuccessful) {
                        Log.d(TAG, "FCMPC ✅ FCM: Active status reported successfully (status=$status)")
                        response.body?.string()?.let { body ->
                            Log.d(TAG, "FCMPC 📶 FCM: Backend response: $body")
                        }
                    } else {
                        Log.e(TAG, "FCMPC ❌ FCM: Backend returned error for active status: ${response.code}")
                    }
                    response.close()
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "FCMPC ❌ FCM: Exception reporting active status: ${e.message}")
        }
    }

    /**
     * Get device IMEI
     */
    private fun getDeviceImei(): String? {
        return try {
            // Use saved IMEI from SharedPreferences if available
            val prefs = getSharedPreferences("user_prefs", MODE_PRIVATE)
            prefs.getString("user_imei1", null)
        } catch (e: Exception) {
            Log.e(TAG, "FCMPC ❌ Failed to get device IMEI: ${e.message}")
            null
        }
    }
    
    /**
     * 🎯 CRITICAL FIX: Check if FCM message was already processed
     */
    private fun isMessageAlreadyProcessed(messageId: String): Boolean {
        return try {
            val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            val processedMessages = prefs.getStringSet(KEY_PROCESSED_MESSAGES, mutableSetOf()) ?: mutableSetOf()
            processedMessages.contains(messageId)
        } catch (e: Exception) {
            Log.e(TAG, "FCMPC ❌ Failed to check processed messages: ${e.message}")
            false
        }
    }
    
    /**
     * 🎯 CRITICAL FIX: Mark FCM message as processed
     */
    private fun markMessageAsProcessed(messageId: String) {
        try {
            val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            val processedMessages = prefs.getStringSet(KEY_PROCESSED_MESSAGES, mutableSetOf())?.toMutableSet() ?: mutableSetOf()
            
            // Add new message ID
            processedMessages.add(messageId)
            
            // Keep only last 100 message IDs to prevent storage bloat
            if (processedMessages.size > 100) {
                val toRemove = processedMessages.take(processedMessages.size - 100)
                processedMessages.removeAll(toRemove)
            }
            
            // Save back to preferences
            prefs.edit().putStringSet(KEY_PROCESSED_MESSAGES, processedMessages).apply()
            
            Log.d(TAG, "FCMPC ✅ Message $messageId marked as processed (total: ${processedMessages.size})")
        } catch (e: Exception) {
            Log.e(TAG, "FCMPC ❌ Failed to mark message as processed: ${e.message}")
        }
    }
}
