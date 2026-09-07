package com.renew.jss.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.json.JSONObject
import android.content.SharedPreferences
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import com.renew.jss.policy.PolicyChangeProcessor
import com.renew.jss.DeviceOwnerReceiver
import android.os.PowerManager
import android.app.AlarmManager
import android.os.SystemClock
import android.app.PendingIntent
import android.os.Handler
import android.os.Looper
import androidx.work.WorkManager
import com.renew.jss.utils.TTSHelper
import com.renew.jss.utils.NotificationHelper
import com.renew.jss.ApiConfig
import com.renew.jss.storage.LockedStateStore
import com.renew.jss.policy.PolicyDispatcher
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

class PolicyMonitoringService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var isNetworkAvailable = false

    companion object {
        private const val TAG = "FCMPC_PolicyMonitoringService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "protection_channel_v2"
        private const val PREFS_NAME = "policy_storage"
        private const val KEY_LAST_POLICY = "last_policy_json"
        private const val KEY_LAST_FETCH = "last_fetch_time"

        @Volatile
        var isRunning = false
    }

    private var prefs: SharedPreferences? = null
    private var policyProcessor: PolicyChangeProcessor? = null
    // Opt #8: Track whether startForeground() has been called on this instance.
    // onStartCommand fires 6+ times per cold start; startForeground only needs calling once.
    private var foregroundStarted = false

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("${ApiConfig.GENERIC_APP_NAME} System")
            .setContentText("Device protection is active")
            .setOngoing(true) //
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setForegroundServiceBehavior(
                NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE
            )
            .build()
    }



    override fun onCreate() {
        super.onCreate()

        createNotificationChannel()

        // Initialize native storage
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // Initialize policy processor
        try {
            policyProcessor = PolicyChangeProcessor.getInstance(this)
        } catch (e: Exception) {
            Log.e(TAG, "â Œ Failed to initialize PolicyChangeProcessor: ${e.message}", e)
        }

        // ⚠️ RACE CONDITION FIX: reapplyPersistedPolicies() has been intentionally removed
        //    from onCreate(). Previously it ran on a background thread here, which caused a
        //    race against onStartCommand()'s processPolicyPayload():
        //
        //    Thread A (onStartCommand): applies new FCM policy (e.g. hide_application=true)
        //    Thread B (onCreate background): reads OLD SharedPrefs (hide_application=false)
        //                                   → re-applies old state, UNDOING Thread A's work
        //
        //    reapplyPersistedPolicies() is still called in onStartCommand's else-branch for
        //    explicit non-policy service starts, and on sticky restarts the kiosk block below
        //    handles the only critical case (re-entering kiosk if locked).


        // âœ… ANR FIX: Read lock state quickly, then defer all heavy work
        val isLocked = LockedStateStore.isLocked(this)
        val isKioskEnabled = com.renew.jss.storage.KioskStateManager.isKioskEnabled(this)

        if (isLocked && isKioskEnabled) {
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    val enforcementIntent = Intent(this, KioskEnforcementService::class.java)
                    startForegroundService(enforcementIntent)

                    val kioskIntent = Intent(this, com.renew.jss.activity.KioskActivity::class.java)
                    kioskIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    kioskIntent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    kioskIntent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
                    startActivity(kioskIntent)
                } catch (e: Exception) {
                    Log.e(TAG, "âŒ Deferred kiosk start failed: ${e.message}")
                }
            }, 800L)
        } else {
            Log.d(TAG, "ðŸ”“ Device not locked or kiosk not enabled - skipping enforcement")
        }

        // âœ… ANR FIX: Only acquire WakeLock if not already held
        if (wakeLock == null || wakeLock?.isHeld == false) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "${ApiConfig.APP_NAME}::FCMLock"
            )
            wakeLock?.setReferenceCounted(false)
            wakeLock?.acquire(10 * 60 * 1000L)
        } else {
        }

        // âœ… ANR FIX: TTS init on background thread
        Thread {
            try {
                TTSHelper.getInstance().initializeTTS(this) { success ->
                }
            } catch (e: Exception) {
                Log.e(TAG, "âŒ TTS init failed: ${e.message}")
            }
        }.start()

        // Apply battery optimization protection
        applyBatteryOptimization()

        isRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Opt #8: Only call startForeground() on the first onStartCommand invocation.
        // Subsequent calls on the same instance are no-ops — the service is already in foreground.
        if (!foregroundStarted) {
            createNotificationChannel()
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, buildNotification(), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, buildNotification())
            }
            foregroundStarted = true
        }

        // ðŸš€ Handle FCM Commands (like competitor)
        when {
            intent?.hasExtra("policy_data") == true -> {
                // ðŸš€ FCM Policy Update - Process directly like socket
                val policyDataStr = intent.getStringExtra("policy_data")
                val commandId = intent.getStringExtra("command_id") ?: "0"
                val source = intent.getStringExtra("source") ?: "fcm"
                
                Log.d(TAG, "FCMPC ðŸš€ Processing policy from $source - Command ID: $commandId")
                Log.d(TAG, "FCMPC ðŸš€ Policy data: $policyDataStr")
                
                try {
                    val policyData = JSONObject(policyDataStr ?: "{}")
                    
                    // Parse all required fields for logging (same as socket)
                    Log.d("POLICY", "FCMPC ðŸš€ FCM lock_device: ${policyData.optBoolean("lock_device", false)}")
                    Log.d("POLICY", "FCMPC ðŸš€ FCM camera_enabled: ${policyData.optBoolean("camera_enabled", true)}")
                    Log.d("POLICY", "FCMPC ðŸš€ FCM wifi_enabled: ${policyData.optBoolean("wifi_enabled", true)}")
                    Log.d("POLICY", "FCMPC ðŸš€ FCM mobile_data_enabled: ${policyData.optBoolean("mobile_data_enabled", true)}")
                    Log.d("POLICY", "FCMPC ðŸš€ FCM usb_debugging_enabled: ${policyData.optBoolean("usb_debugging_enabled", true)}")
                    Log.d("POLICY", "FCMPC ðŸš€ FCM offline_lock: ${policyData.optBoolean("offline_lock", false)}")
                    Log.d("POLICY", "FCMPC ðŸš€ FCM factory_reset_enabled: ${policyData.optBoolean("factory_reset_enabled", true)}")
                    Log.d("POLICY", "FCMPC ðŸš€ FCM calls_enabled: ${policyData.optBoolean("calls_enabled", true)}")
                    Log.d("POLICY", "FCMPC ðŸš€ FCM social_apps_enabled: ${policyData.optBoolean("social_apps_enabled", true)}")
                    Log.d("POLICY", "FCMPC ðŸš€ FCM get_sim_info: ${policyData.optBoolean("get_sim_info", false)}")
                    Log.d("POLICY", "FCMPC ðŸš€ FCM get_location: ${policyData.optBoolean("get_location", false)}")
                    
                    // Process policy changes through PolicyChangeProcessor (same flow as socket)
                    policyProcessor?.processPolicyPayload(policyData, commandId)
                    
                    Log.d("COMMAND_ID", "FCMPC ðŸš€ FCM Command ID: $commandId")
                    Log.d(TAG, "FCMPC âœ… FCM Policy processed successfully")
                    
                } catch (e: Exception) {
                    Log.e(TAG, "FCMPC âŒ Failed to process FCM policy: ${e.message}")
                }
            }
            
            intent?.hasExtra("audio_reminder_text") == true -> {
                // ðŸ”Š FCM Audio Reminder - Handle like competitor
                val reminderText = intent.getStringExtra("audio_reminder_text") ?: "EMI payment reminder"
                val reminderLanguage = intent.getStringExtra("audio_reminder_language") ?: "en"
                val nextEmiDate = intent.getStringExtra("next_emi_date") ?: ""
                val shopName = intent.getStringExtra("shop_name") ?: ""
                
                Log.d(TAG, "FCMPC ðŸ”Š Processing audio reminder from FCM")
                Log.d(TAG, "FCMPC ðŸ”Š Text: $reminderText, Language: $reminderLanguage")
                
                // TODO: Implement audio reminder processing (like competitor TTS)
                handleAudioReminder(reminderText, reminderLanguage, nextEmiDate, shopName)
            }
            
            intent?.getBooleanExtra("fetch_gps_command", false) == true -> {
                // soc-loc GPS fetch command
                Log.d(TAG, "FCMPC soc-loc ðŸš€ Processing GPS fetch from FCM")
                handleFetchGpsCommand()
            }
            
            intent?.getBooleanExtra("remove_mobile_command", false) == true -> {
                // ðŸ“µ Remove mobile restrictions command
                Log.d(TAG, "FCMPC ðŸ“µ Processing remove mobile restrictions from FCM")
                handleRemoveMobileCommand()
            }
            
            intent?.getBooleanExtra("disable_accessibility_command", false) == true -> {
                // â™¿ Disable accessibility command
                Log.d(TAG, "FCMPC â™¿ Processing DISABLE_ACCESSIBILITY command from FCM")
                handleDisableAccessibilityCommand()
            }

            intent?.getBooleanExtra("enable_accessibility_command", false) == true -> {
                // â™¿ Enable accessibility command
                Log.d(TAG, "FCMPC â™¿ Processing ENABLE_ACCESSIBILITY command from FCM")
                handleEnableAccessibilityCommand()
            }

            else -> {
                // Normal service startup - FCM only
                // âœ… ANR FIX: Only reapply policies if this is a FRESH start (not a sticky restart).
                // On sticky restart intent is null â€” skipping reapply prevents redundant work
                // and repeated KioskActivity launches that pile up across open-close cycles.
                if (intent != null) {
                    Log.d(TAG, "FCMPC ðŸš€ Normal explicit service start â€” running reapplyPersistedPolicies")
                    reapplyPersistedPolicies()
                } else {
                    Log.d(TAG, "FCMPC ðŸ”„ Sticky restart detected (intent=null) â€” skipping reapplyPersistedPolicies")
                }
            }
        }

        return START_STICKY
    }

    /**
     * ðŸ”Š Handle Audio Reminder (like competitor TTS)
     */
    private fun handleAudioReminder(reminderText: String, language: String, nextEmiDate: String, shopName: String) {
        Log.d(TAG, "FCMPC ðŸ”Š Processing audio reminder: $reminderText")
        
        try {
            // Speak the English lead-in ([reminderText], default "EMI payment reminder")
            // followed by the fixed Hindi reminder, in a best-effort female voice.
            TTSHelper.getInstance().speakEmiReminderBilingual(this, reminderText) {
                Log.d(TAG, "FCMPC 🔊 Audio reminder completed")
            }

            // Show notification as well. (Named args — this method's signature is
            // reminderText, shopName, nextEmiDate, reminderLanguage.)
            NotificationHelper.showEmiReminder(
                context = this,
                reminderText = reminderText,
                shopName = shopName,
                nextEmiDate = nextEmiDate,
                reminderLanguage = language
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "FCMPC ðŸ”Š Failed to process audio reminder: ${e.message}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, " PolicyMonitoringService.onDestroy() called")

        // SIMPLIFIED: Release wake lock only
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
            Log.d(TAG, " WakeLock released")
        }

        // Shutdown TTS
        TTSHelper.getInstance().shutdown()

        isRunning = false
        Log.d(TAG, " PolicyMonitoringService.onDestroy() completed - Service will be restarted by FcmHeartbeatWorker")
    }

    private fun applyBatteryOptimization() {
        try {
            val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val admin = ComponentName(this, DeviceOwnerReceiver::class.java)

            // Prevent OEM battery optimization from killing this service (admin permissions only)
            // Note: setBatteryOptimizationEnabled requires device owner, so we skip this for admin-only

            Log.d(TAG, " Battery optimization check skipped (admin permissions only)")
        } catch (e: Exception) {
            Log.w(TAG, " Battery optimization check failed: ${e.message}")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
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

    private fun reapplyPersistedPolicies() {
        policyProcessor?.reapplyPersistedPolicies()

        //  WORKMANAGER FALLBACK - Like HR Finance competitor
        PolicyCheckWorker.schedulePeriodicCheck(applicationContext)
        Log.d(TAG, " WorkManager fallback scheduled")
    }

    private fun getDeviceImei(): String? {
        return try {
            // Use saved IMEI from SharedPreferences if available
            val prefs = getSharedPreferences("user_prefs", MODE_PRIVATE)
            val savedImei1 = prefs.getString("user_imei1", null)
            
            if (!savedImei1.isNullOrEmpty()) {
                Log.d(TAG, "ðŸ“± Using saved IMEI: $savedImei1")
                return savedImei1
            }
            
            // Fallback to device IMEI if not saved yet
            val telephony =
                getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

            val imei =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    // Try IMEI1 first
                    var imei = telephony.getImei(0)
                    if (imei.isNullOrEmpty()) {
                        Log.w(TAG, " IMEI1 is null or empty, trying alternative methods...")
                        
                        // Fallback: Try device ID for device owner
                        try {
                            @Suppress("DEPRECATION")
                            imei = telephony.deviceId
                            if (!imei.isNullOrEmpty()) {
                                Log.d(TAG, " Using deviceId as fallback: $imei")
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "deviceId fallback failed: ${e.message}")
                        }
                        
                        // Final fallback: Try serial number for device owner
                        if (imei.isNullOrEmpty()) {
                            try {
                                imei = android.os.Build.getSerial()
                                if (!imei.isNullOrEmpty() && imei != "unknown") {
                                    Log.d(TAG, " Using serial number as fallback: $imei")
                                } else {
                                    imei = null
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "Serial number fallback failed: ${e.message}")
                            }
                        }
                    }
                    imei
                } else {
                    @Suppress("DEPRECATION")
                    telephony.deviceId
                }

            imei?.takeIf { it.isNotEmpty() }
        } catch (e: SecurityException) {
            Log.e(TAG, " SecurityException getting IMEI: ${e.message}")
            // For device owner, try alternative identifier
            try {
                val serial = android.os.Build.getSerial()
                if (!serial.isNullOrEmpty() && serial != "unknown") {
                    Log.d(TAG, " Using serial number as IMEI fallback: $serial")
                    serial
                } else {
                    null
                }
            } catch (ex: Exception) {
                Log.e(TAG, "Failed to get serial fallback: ${ex.message}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, " Failed to get IMEI: ${e.message}")
            // Final fallback: Generate persistent device identifier
            generateDeviceIdentifier()
        }
    }

    /**
     * Generate a persistent device identifier as last resort
     */
    private fun generateDeviceIdentifier(): String {
        val prefs = getSharedPreferences("device_identifiers", Context.MODE_PRIVATE)
        var deviceId = prefs.getString("persistent_device_id", null)

        if (deviceId == null) {
            // Generate unique ID based on device hardware info
            val buildInfo = "${android.os.Build.BRAND}_${android.os.Build.MODEL}_${android.os.Build.ID}_${System.currentTimeMillis()}"
            deviceId = "DEVICE_${buildInfo.hashCode().toString(16).uppercase()}"

            // Persist the generated ID
            prefs.edit().putString("persistent_device_id", deviceId).apply()
            Log.d(TAG, " Generated persistent device identifier: $deviceId")
        } else {
            Log.d(TAG, " Using existing persistent device identifier: $deviceId")
        }

        return deviceId
    }

    /**
     * Check if network is available
     */
    private fun isNetworkAvailable(): Boolean {
        return try {
            val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val network = connectivityManager.activeNetwork
                val capabilities = connectivityManager.getNetworkCapabilities(network)
                capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
            } else {
                @Suppress("DEPRECATION")
                connectivityManager.activeNetworkInfo?.isConnected == true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking network availability: ${e.message}")
            false
        }
    }

    /**
     * Handle FETCH_GPS command
     */
    private fun handleFetchGpsCommand() {
        Log.d(TAG, "soc-loc Handling FETCH_GPS command")
        GpsFetchService.handleGpsFetchCommand(this)
    }

    /**
     * ðŸ“µ Handle REMOVE_MOBILE command from FCM (same as socket)
     */
    private fun handleRemoveMobileCommand() {
        Log.d(TAG, "FCMPC ðŸ“µ Handling REMOVE_MOBILE command from FCM")
        
        try {
            // Apply remove all restrictions policy (same as socket implementation)
            Log.d(TAG, "FCMPC ðŸ“µ Applying REMOVE_ALL_RESTRICTIONS policy from FCM")
            PolicyDispatcher.apply(this, "REMOVE_ALL_RESTRICTIONS", true)
            
            Log.d(TAG, "FCMPC ðŸ“µ All restrictions removed successfully via FCM")
            
        } catch (e: Exception) {
            Log.e(TAG, "FCMPC ðŸ“µ Failed to handle REMOVE_MOBILE command from FCM: ${e.message}")
        }
    }

    /**
     * â™¿ Handle DISABLE_ACCESSIBILITY command
     */
    private fun handleDisableAccessibilityCommand() {
        Log.d(TAG, "FCMPC â™¿ Handling DISABLE_ACCESSIBILITY command")
        try {
            com.renew.jss.service.MyAccessibilityService.disableService(this)
            Log.d(TAG, "FCMPC â™¿ Accessibility service disable request processed")
        } catch (e: Exception) {
            Log.e(TAG, "FCMPC â™¿ Failed to disable accessibility service: ${e.message}")
        }
    }

    /**
     * â™¿ Handle ENABLE_ACCESSIBILITY command — programmatically re-enable our
     * accessibility service via Settings.Secure (needs WRITE_SECURE_SETTINGS).
     */
    private fun handleEnableAccessibilityCommand() {
        Log.d(TAG, "FCMPC â™¿ Handling ENABLE_ACCESSIBILITY command")
        try {
            val ok = com.renew.jss.service.MyAccessibilityService.enableService(this)
            if (ok) {
                Log.d(TAG, "FCMPC â™¿ Accessibility service enable request processed")
            } else {
                Log.w(TAG, "FCMPC â™¿ Enable failed (WRITE_SECURE_SETTINGS not granted?) — user must enable manually")
            }
        } catch (e: Exception) {
            Log.e(TAG, "FCMPC â™¿ Failed to enable accessibility service: ${e.message}")
        }
    }

    /**
     * Handle AUDIO_REMINDER command
     */
    private fun handleAudioReminderCommand(args: Array<Any>) {
        try {
            Log.d(TAG, "Handling AUDIO_REMINDER command")

            // Extract commandId for status tracking if available
            var commandId = 0
            if (args.isNotEmpty()) {
                val payload = args[0] as? JSONObject
                if (payload != null) {
                    commandId = payload.optInt("command_id", 0)
                    Log.d("AUDIO_REMINDER", "Command ID: $commandId")
                }
            }

            // Play fixed reminder: English lead-in "EMI payment reminder" followed by the
            // Hindi sentence, in a best-effort female voice (regardless of payload content).
            val notificationText =
                "${TTSHelper.ENGLISH_EMI_REMINDER}\n${TTSHelper.HINDI_EMI_REMINDER}"
            Log.d("AUDIO_REMINDER", "Playing fixed bilingual reminder")

            TTSHelper.getInstance().speakEmiReminderBilingual(this) {
                Log.d("AUDIO_REMINDER", "Audio reminder completed")
            }

            // Show notification with the fixed message.
            NotificationHelper.showEmiReminder(
                context = this,
                reminderText = notificationText,
                shopName = null,
                nextEmiDate = null,
                reminderLanguage = "hi-IN"
            )
            
            // Update command status if commandId is provided
            if (commandId > 0) {
                Log.d(TAG, "Reporting audio reminder command $commandId as completed...")
                updateCommandStatus(commandId, "completed")
            }
            
        } catch (e: Exception) {
            Log.e("AUDIO_REMINDER", "Failed to handle audio reminder: ${e.message}")
        }
    }

    /**
     * API Call to update command status to 'completed'
     */
    private fun updateCommandStatus(commandId: Int, status: String) {
        // Check network availability before making HTTP call
        if (!isNetworkAvailable()) {
            Log.w(TAG, "Network not available. Skipping command status update for command $commandId")
            return
        }
        
        Thread {
            try {
                // REPLACE WITH YOUR ACTUAL API URL
                val urlString = ApiConfig.Api.UPDATE_COMMAND_STATUS

                val jsonBody = org.json.JSONObject().apply {
                    put("command_id", commandId)
                    put("status", status)
                }

                val mediaType = "application/json; charset=utf-8".toMediaType()
                val requestBody = jsonBody.toString().toRequestBody(mediaType)
                
                val request = okhttp3.Request.Builder()
                    .url(urlString)
                    .post(requestBody)
                    .build()
                
                val client = okhttp3.OkHttpClient()
                client.newCall(request).execute().use { response ->
                    Log.d(TAG, "Command $commandId updated to '$status'. Response: ${response.code}")
                }

            } catch (e: java.net.SocketTimeoutException) {
                Log.w(TAG, "Command status update timeout for command $commandId: ${e.message}")
            } catch (e: java.net.UnknownHostException) {
                Log.w(TAG, "No internet connection for command status update $commandId: ${e.message}")
            } catch (e: java.io.IOException) {
                Log.w(TAG, "Network error updating command status $commandId: ${e.message}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update command status: ${e.message}")
            }
        }.start()
    }
}



