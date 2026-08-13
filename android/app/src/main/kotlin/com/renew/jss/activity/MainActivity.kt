package com.renew.jss.activity

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.os.Build
import android.os.UserManager
import android.content.Intent
import android.util.Log
import android.provider.Settings
import androidx.core.app.ActivityCompat
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.*
import java.io.IOException
import java.security.SecureRandom
import android.telephony.TelephonyManager
import android.telephony.SubscriptionManager
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

import com.renew.jss.storage.AuthorizedNumbersStore
import com.renew.jss.DeviceOwnerReceiver
import com.renew.jss.service.PolicyMonitoringService
import com.renew.jss.service.AlwaysAliveService
import com.renew.jss.service.PreventiveService
import com.renew.jss.service.GpsFetchService
import com.renew.jss.device.DeviceController
import com.renew.jss.workers.FcmHeartbeatWorker

import com.renew.jss.permission.HardeningPermission
import com.renew.jss.permission.HardeningPermissionManager
import com.renew.jss.permission.OemSettingsHelper
import com.renew.jss.ApiConfig
import com.renew.jss.BuildConfig
import com.google.firebase.messaging.FirebaseMessaging

class MainActivity : FlutterActivity() {

    private val CHANNEL = "com.renew.jss/admin"
    companion object {
        private const val TAG = "FCMPC_MainActivity"
    }
    private val client = OkHttpClient()
    private val mainScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Initialize default authorized numbers
        AuthorizedNumbersStore.initializeDefaults(this)
        
        // ðŸš€ ALWAYS START SERVICES if already established (like competitor)
        startMonitoringService()
        
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(this, DeviceOwnerReceiver::class.java)

        if (dpm.isAdminActive(admin)) {
            
            // Check if this is first time app is opened as device admin
            val prefs = getSharedPreferences("device_admin_setup", MODE_PRIVATE)
            val isFirstTimeAsDeviceAdmin = !prefs.getBoolean("setup_completed", false)
            
            if (!isFirstTimeAsDeviceAdmin) {
                // Only execute if app is already established as device admin
                Log.d(TAG, "App is already established as device admin - applying normal startup procedures")
                
                // Removed Device Owner setup code here (auto-grant permissions, user restrictions, clear home)
                // Removed FRP auto-apply: setApplicationRestrictions() is Device Owner/Profile Owner
                // only and throws SecurityException under Device Admin. This is a running DPC.

                // ðŸš€ Start FCM Heartbeat Worker (like competitor)
                FcmHeartbeatWorker.startHeartbeat(this)
                Log.d(TAG, "ðŸš€ FCM Heartbeat worker started")

                // ðŸ”„ Start Periodic Policy Sync (WorkerManager Fallback)
                com.renew.jss.service.PolicyCheckWorker.schedulePeriodicCheck(this)
                Log.d(TAG, "ðŸ”„ PolicyCheckWorker scheduled")
                
            } else {
                Log.d(TAG, "First time as device admin - marking setup as completed automatically")
                
                // ðŸš€ AUTO-COMPLETE SETUP ON FIRST DEVICE ADMIN DETECTION
                prefs.edit().putBoolean("setup_completed", true).apply()
                Log.d(TAG, "âœ… Device admin setup automatically marked as completed")
                
                Log.d(TAG, "ðŸ”’ Applying all startup procedures after auto-setup completion...")
                
                // Removed Device Owner setup code here (auto-grant permissions, clear home)
                // Removed FRP auto-apply: Device Owner/Profile Owner only, unusable under Device Admin.
            }
        }
    }

    override fun onResume() {
        super.onResume()
        checkHardeningStatus()
    }

    private fun checkHardeningStatus() {
        // ðŸ›¡ï¸ DISABLED: This activity interferes with setup.
        /*
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(this, DeviceOwnerReceiver::class.java)
        
        // Only enforce after setup is completed
        val prefs = getSharedPreferences("device_admin_setup", MODE_PRIVATE)
        if (prefs.getBoolean("setup_completed", false)) {
            val isAdminActive = dpm.isAdminActive(admin)
            val isAccessibilityEnabled = isAccessibilityServiceEnabled(this)
            
            if (!isAdminActive || !isAccessibilityEnabled) {
                Log.w(TAG, "ðŸš¨ CRITICAL: Hardening missing in MainActivity! Launching Enforcement Activity...")
                val intent = Intent(this, PermissionEnforcementActivity::class.java)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
            }
        }
        */
    }

    private fun isAccessibilityServiceEnabled(context: Context): Boolean {
        // ðŸ›¡ï¸ DUAL CHECK: Check Settings.Secure + AccessibilityManager
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? android.view.accessibility.AccessibilityManager
        val isServiceRunning = am?.isEnabled == true && 
            am.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_GENERIC)
              ?.any { it.resolveInfo.serviceInfo.packageName == context.packageName } == true
        
        if (isServiceRunning) return true

        val expectedService = ComponentName(context, com.renew.jss.service.MyAccessibilityService::class.java).flattenToString()
        val enabledServices = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        return enabledServices != null && (enabledServices.contains(expectedService) || enabledServices.contains(context.packageName))
    }




    // ================= SERVICE CONTROL =================

    private fun startMonitoringService() {
        try {
            // Check if app is device admin and setup is completed
            val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val admin = ComponentName(this, DeviceOwnerReceiver::class.java)
            
            if (dpm.isAdminActive(admin)) {
                val prefs = getSharedPreferences("device_admin_setup", MODE_PRIVATE)
                val isSetupCompleted = prefs.getBoolean("setup_completed", false)
                
                if (isSetupCompleted) {
                    // Opt #2: Skip if services are already running (avoids 6+ redundant starts)
                    if (PolicyMonitoringService.isRunning) {
                        Log.d(TAG, "⏭️ startMonitoringService: services already running, skipping")
                    } else {
                        Log.d(TAG, "✅ Device admin setup completed - starting all monitoring services")
                        val startHandler = android.os.Handler(android.os.Looper.getMainLooper())
                        startHandler.post {
                            startServiceCompat(PolicyMonitoringService::class.java)
                        }
                        startHandler.postDelayed({
                            startServiceCompat(AlwaysAliveService::class.java)
                        }, 300L)
                        startHandler.postDelayed({
                            startServiceCompat(PreventiveService::class.java)
                        }, 600L)
                        Log.d(TAG, "✅ All monitoring services scheduled (staggered)")
                    }
                } else {
                    Log.d(TAG, "â­ï¸ Device admin setup not completed - skipping monitoring service start")
                }
            } else {
                Log.d(TAG, "â­ï¸ App is not device admin - skipping monitoring service start")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "âŒ Failed to start monitoring services: ${e.message}")
        }
    }

    /**
     * COMPATIBLE SERVICE STARTER
     */
    private fun startServiceCompat(serviceClass: Class<*>) {
        val intent = Intent(this, serviceClass)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    // ================= FLUTTER BRIDGE =================

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            CHANNEL
        ).setMethodCallHandler { call, result ->

        

            when (call.method) {

             

                "startService" -> {
                    try {
                        val intent = Intent(this, PolicyMonitoringService::class.java)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            startForegroundService(intent)
                        } else {
                            startService(intent)
                        }
                        result.success(true)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to start policy monitoring service: ${e.message}")
                        result.error("SERVICE_ERROR", e.message, null)
                    }
                }

                "isPolicyServiceRunning" -> {
                    result.success(PolicyMonitoringService.isRunning)
                }

              

               

                // ----- DEVICE INFO -----
                "getImei" -> {
                    result.success(getDeviceImeis())
                }
                
                "getFlavor" -> {
                    result.success(BuildConfig.FLAVOR)
                }

                // Device hardware identity for enrollment (manufacturer/brand/model).
                "getDeviceInfo" -> {
                    try {
                        val info = hashMapOf(
                            "manufacturer" to android.os.Build.MANUFACTURER,
                            "brand" to android.os.Build.BRAND,
                            "model" to android.os.Build.MODEL
                        )
                        result.success(info)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to get device info: ${e.message}")
                        result.error("DEVICE_INFO_ERROR", e.message, null)
                    }
                }
                "saveUserImei" -> {
                    try {
                        val imei1 = call.argument<String>("imei1")
                        val imei2 = call.argument<String>("imei2")
                        
                        if (imei1 != null && imei2 != null) {
                            saveUserImei(imei1, imei2)
                            result.success(true)
                            Log.d(TAG, "âœ… IMEI saved from Flutter: IMEI1=$imei1, IMEI2=$imei2")
                        } else {
                            result.error("INVALID_ARGS", "IMEI1 and IMEI2 required", null)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to save IMEI: ${e.message}")
                        result.error("SAVE_IMEI_ERROR", e.message, null)
                    }
                }

                // ----- PERMISSIONS -----
                "isIgnoringBatteryOptimizations" -> {
                    val manager = HardeningPermissionManager(this)
                    result.success(manager.isPermissionGranted(HardeningPermission.BATTERY_OPTIMIZATION))
                }

                "requestIgnoreBatteryOptimizations" -> {
                    try {
                        val manager = HardeningPermissionManager(this)
                        val manufacturer = android.os.Build.MANUFACTURER.lowercase()
                        
                        when {
                            manufacturer.contains("xiaomi") || manufacturer.contains("redmi") -> 
                                com.renew.jss.utils.OemOptimizer.openXiaomiAutostart(this)
                            manufacturer.contains("vivo") -> 
                                com.renew.jss.utils.OemOptimizer.openVivoOptimization(this)
                            manufacturer.contains("oppo") || manufacturer.contains("realme") -> 
                                com.renew.jss.utils.OemOptimizer.openOppoOptimization(this)
                            else -> {
                                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                                intent.data = android.net.Uri.parse("package:$packageName")
                                startActivity(intent)
                            }
                        }
                        
                        // ðŸŽ¯ MARK AS SHOWN: This turns the tile green so user can proceed
                        manager.markPromptShown(com.renew.jss.permission.HardeningPermission.BATTERY_OPTIMIZATION)
                        result.success(true)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to request battery optimization: ${e.message}")
                        result.error("BATTERY_OPTIMIZATION_ERROR", e.message, null)
                    }
                }
                
                "checkAllPermissions" -> {
                    val manager = HardeningPermissionManager(this)
                    val permissions = mapOf(
                        "deviceAdmin" to manager.isPermissionGranted(HardeningPermission.DEVICE_ADMIN),
                        "accessibility" to manager.isPermissionGranted(HardeningPermission.ACCESSIBILITY),
                        "overlay" to manager.isPermissionGranted(HardeningPermission.OVERLAY),
                        "location" to manager.isPermissionGranted(HardeningPermission.LOCATION),
                        "sms" to manager.isPermissionGranted(HardeningPermission.SMS),
                        "phone" to manager.isPermissionGranted(HardeningPermission.PHONE),
                        "notification" to manager.isPermissionGranted(HardeningPermission.NOTIFICATION),
                        "batteryOptimization" to manager.isPermissionGranted(HardeningPermission.BATTERY_OPTIMIZATION)
                    )
                    result.success(permissions)
                }
                
                "requestDeviceAdmin" -> {
                    try {
                        val intent = Intent("android.app.action.ADD_DEVICE_ADMIN")
                        intent.putExtra("android.app.extra.DEVICE_ADMIN", 
                            ComponentName(this, DeviceOwnerReceiver::class.java))
                        intent.putExtra("android.app.extra.ADD_EXPLANATION", 
                            "This app needs device administrator privileges to protect your device.")
                        startActivityForResult(intent, 100)
                        result.success(true)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to request device admin: ${e.message}")
                        result.error("DEVICE_ADMIN_ERROR", e.message, null)
                    }
                }
                
                "requestAccessibility" -> {
                    try {
                        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        startActivity(intent)
                        result.success(true)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to open accessibility settings: ${e.message}")
                        result.error("ACCESSIBILITY_ERROR", e.message, null)
                    }
                }
                
                "requestOverlay" -> {
                    try {
                        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                        intent.data = android.net.Uri.parse("package:$packageName")
                        startActivity(intent)
                        result.success(true)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to open overlay settings: ${e.message}")
                        result.error("OVERLAY_ERROR", e.message, null)
                    }
                }
                
                "requestLocation" -> {
                    try {
                        ActivityCompat.requestPermissions(this, arrayOf(
                            android.Manifest.permission.ACCESS_FINE_LOCATION,
                            android.Manifest.permission.ACCESS_COARSE_LOCATION
                        ), 103)
                        result.success(true)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to request location: ${e.message}")
                        result.error("LOCATION_ERROR", e.message, null)
                    }
                }
                
                "requestSms" -> {
                    try {
                        ActivityCompat.requestPermissions(this, arrayOf(
                            android.Manifest.permission.RECEIVE_SMS,
                            android.Manifest.permission.READ_SMS
                        ), 104)
                        result.success(true)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to request SMS: ${e.message}")
                        result.error("SMS_ERROR", e.message, null)
                    }
                }
                
                "requestPhone" -> {
                    try {
                        ActivityCompat.requestPermissions(this, arrayOf(
                            android.Manifest.permission.READ_PHONE_STATE,
                            android.Manifest.permission.READ_PHONE_NUMBERS
                        ), 105)
                        result.success(true)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to request phone: ${e.message}")
                        result.error("PHONE_ERROR", e.message, null)
                    }
                }
                
                "requestNotification" -> {
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            ActivityCompat.requestPermissions(this, arrayOf(
                                android.Manifest.permission.POST_NOTIFICATIONS
                            ), 106)
                        }
                        result.success(true)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to request notification: ${e.message}")
                        result.error("NOTIFICATION_ERROR", e.message, null)
                    }
                }

                "hasOverlayPermission" -> {
                    val manager = HardeningPermissionManager(this)
                    result.success(manager.isPermissionGranted(HardeningPermission.OVERLAY))
                }

                // ----- DPC SERVICES -----
                // Opt #4: initializeFCM removed — Firebase auto-initializes via ContentProvider

                "startNotificationService" -> {
                    // âœ… ANR FIX: Do NOT start MyFirebaseMessagingService with startForegroundService.
                    // FirebaseMessagingService manages its own lifecycle via the Firebase SDK.
                    // Manually calling startForegroundService() on it means it NEVER calls
                    // startForeground() â†’ Android throws ForegroundServiceDidNotStartInTimeException
                    // exactly 5 seconds later â†’ this was the root cause of the ANR.
                    result.success(true)
                }


                "startBackgroundMonitoring" -> {
                    try {
                        val policyIntent = Intent()
                        policyIntent.setClassName(this, "com.renew.jss.service.PolicyMonitoringService")
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            startForegroundService(policyIntent)
                        } else {
                            startService(policyIntent)
                        }
                        
                        val alwaysAliveIntent = Intent()
                        alwaysAliveIntent.setClassName(this, "com.renew.jss.service.AlwaysAliveService")
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            startForegroundService(alwaysAliveIntent)
                        } else {
                            startForegroundService(alwaysAliveIntent)
                        }
                        
                        val preventiveIntent = Intent()
                        preventiveIntent.setClassName(this, "com.renew.jss.service.PreventiveService")
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            startForegroundService(preventiveIntent)
                        } else {
                            startService(preventiveIntent)
                        }
                        result.success(true)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to start background monitoring: ${e.message}")
                        result.error("BACKGROUND_MONITORING_ERROR", e.message, null)
                    }
                }

                // Opt #4: initializeDeviceAdmin, startAppMonitoring, startCommunicationMonitoring
                // removed — all were instant no-ops handled by PolicyMonitoringService already.

                "startLocationMonitoring" -> {
                    try {
                        // Opt #3: GpsFetchService is a Kotlin object (utility), NOT a Service.
                        // Calling startForegroundService on it was dead code — it never started.
                        // Now we invoke it directly on a background thread; its internal calls
                        // (FusedLocationProviderClient) are already async-safe.
                        Thread { GpsFetchService.handleGpsFetchCommand(applicationContext) }.start()
                        result.success(true)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to start location monitoring: ${e.message}")
                        result.error("LOCATION_MONITORING_ERROR", e.message, null)
                    }
                }

                "applyAdminStartup" -> {
                    try {
                        Log.d(TAG, "applyAdminStartup invoked from Flutter - applying startup procedures")

                        val prefs = getSharedPreferences("device_admin_setup", MODE_PRIVATE)
                        prefs.edit().putBoolean("setup_completed", true).apply()

                        mainScope.launch {
                            try {
                                sendSavedFcmToken()
                            } catch (_: Exception) {}
                            try {
                                FcmHeartbeatWorker.startHeartbeat(this@MainActivity)
                            } catch (_: Exception) {}
                            try {
                                startMonitoringService()
                            } catch (_: Exception) {}
                        }

                        result.success(true)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to apply admin startup: ${e.message}")
                        result.error("APPLY_STARTUP_ERROR", e.message, null)
                    }
                }

                "refreshToken" -> {
                    try {
                        Log.d(TAG, "FCMPC ðŸš€ Forced Token Refresh requested from Flutter")
                        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                            if (!task.isSuccessful) {
                                Log.w(TAG, "âŒ Fetching FCM registration token failed", task.exception)
                                result.error("FCM_TOKEN_ERROR", "Failed to fetch FCM token", task.exception?.message)
                                return@addOnCompleteListener
                            }

                            // Get new FCM registration token
                            val token = task.result
                            Log.d(TAG, "FCMPC ðŸš€ Current FCM token fetched: ${token.take(10)}...")
                            
                            // Save token locally
                            val fcmPrefs = getSharedPreferences("fcm_prefs", MODE_PRIVATE)
                            fcmPrefs.edit().putString("fcm_token", token).apply()
                            
                            // Send to backend
                            sendSavedFcmToken()
                            
                            Log.d(TAG, "âœ… Forced Token Refresh successful")
                            result.success(true)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "âŒ Failed to refresh FCM token: ${e.message}")
                        result.error("REFRESH_TOKEN_ERROR", e.message, null)
                    }
                }

                "removeAllRestrictions" -> {
                    try {
                        // Remove app as Device Admin
                        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                        val admin = ComponentName(this, DeviceOwnerReceiver::class.java)
                        
                        if (dpm.isAdminActive(admin)) {
                            // Exit kiosk mode first
                            com.renew.jss.policy.KioskPolicy.exit(this)
                            
                            // Remove Device Admin
                            dpm.removeActiveAdmin(admin)
                            Log.d(TAG, "âœ… App removed as Device Admin - all restrictions lifted")
                            
                            // Clear stored states
                            val prefs = getSharedPreferences("device_admin_setup", MODE_PRIVATE)
                            prefs.edit().putBoolean("setup_completed", false).apply()
                            
                            result.success(true)
                        } else {
                            Log.w(TAG, "âš ï¸ App is not Device Admin - nothing to remove")
                            result.success(true)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to remove all restrictions: ${e.message}")
                        result.error("REMOVE_RESTRICTIONS_ERROR", e.message, null)
                    }
                }

                "saveRetailerPhone" -> {
                    try {
                        val phone = call.argument<String>("phone")
                        if (phone != null) {
                            val success = AuthorizedNumbersStore.addRetailerNumber(this, phone)
                            result.success(success)
                            Log.d(TAG, "âœ… Retailer phone sync request: $phone, Success: $success")
                        } else {
                            result.error("INVALID_ARGS", "Phone number is required", null)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "âŒ Error saving retailer phone: ${e.message}")
                        result.error("SAVE_PHONE_ERROR", e.message, null)
                    }
                }

                else -> {
                    result.notImplemented()
                }
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        // Handle permission results from device admin request
        when (requestCode) {
            100 -> {
                Log.d(TAG, "Device admin request result: $resultCode")
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // Handle runtime permission results
        Log.d(TAG, "Permission request result for code $requestCode")
    }

    // ================= HELPERS =================

    private fun getDeviceImeis(): Map<String, String?> {
        // Use saved IMEI from SharedPreferences if available, otherwise get from device
        val prefs = getSharedPreferences("user_prefs", MODE_PRIVATE)
        val savedImei1 = prefs.getString("user_imei1", null)
        val savedImei2 = prefs.getString("user_imei2", null)
        
        return if (savedImei1 != null) {
            mapOf("imei1" to savedImei1, "imei2" to savedImei2)
        } else {
            // Fallback to device IMEI if not saved yet
            val telephony = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            val imei1 = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) telephony.getImei(0) else telephony.deviceId
            val imei2 = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) telephony.getImei(1) else null
            mapOf("imei1" to imei1, "imei2" to imei2)
        }
    }
    
    // SAVE USER IMEI PERMANENTLY
    private fun saveUserImei(imei1: String, imei2: String) {
        val prefs = getSharedPreferences("user_prefs", MODE_PRIVATE)
        prefs.edit()
            .putString("user_imei1", imei1)
            .putString("user_imei2", imei2)
            .apply()
        Log.d(TAG, " User IMEI saved permanently: IMEI1=$imei1, IMEI2=$imei2")
    }

    // ================= FRP =================
    // Removed: FRP (factory-reset-protection) via dpm.setApplicationRestrictions() on
    // "com.google.android.gms" is a Device Owner / Profile Owner-only operation and throws
    // SecurityException under Device Admin. This is a running DPC (Device Admin only), so the
    // whole FRP-apply path (fetchAndApplyFrp / fetchGaiaIdFromApi / applyFrpWithGaia / removeFrp)
    // was dead + unusable and has been deleted. The backend FrpData/getFrp endpoints remain
    // unused on the client.

    /**
     * Send saved FCM token to backend after device admin setup is completed
     */
    private fun sendSavedFcmToken() {
        try {
            val fcmPrefs = getSharedPreferences("fcm_prefs", MODE_PRIVATE)
            val savedToken = fcmPrefs.getString("fcm_token", null)
            
            if (!savedToken.isNullOrEmpty()) {
                Log.d(TAG, "ðŸš€ Sending saved FCM token to backend after setup completion")
                
                // Use saved IMEI from SharedPreferences instead of device IMEI
                val imeiPrefs = getSharedPreferences("user_prefs", MODE_PRIVATE)
                val imei = imeiPrefs.getString("user_imei1", null) ?: getDeviceImeis()["imei1"]
                
                if (imei == null) {
                    Log.e(TAG, "âŒ IMEI not available, cannot send saved FCM token")
                    return
                }

                val backendUrl = com.renew.jss.ApiConfig.FCM_TOKEN_ENDPOINT
                // Opt #5: Reuse the class-level OkHttpClient instead of allocating a new
                // instance (thread pool + connection pool) on every FCM token send.
                val httpClient = client.newBuilder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .writeTimeout(30, TimeUnit.SECONDS)
                    .build()

                val isTokenSent = fcmPrefs.getBoolean("fcm_token_sent", false)
                val json = """{"imei": "$imei", "fcmToken": "$savedToken", "first_time": ${!isTokenSent}}"""
                Log.d(TAG, "ðŸš€ Saved FCM token JSON payload = $json")
                Log.d(TAG, "ðŸš€ Saved FCM token backend URL = $backendUrl")
                
                val mediaType = "application/json; charset=utf-8".toMediaType()
                val requestContent = json.toRequestBody(mediaType)
                
                val request = okhttp3.Request.Builder()
                    .url(backendUrl!!)
                    .post(requestContent)
                    .build()

                httpClient.newCall(request).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        Log.e(TAG, "âŒ Failed to send saved FCM token to backend: ${e.message}")
                    }

                    override fun onResponse(call: Call, response: Response) {
                        if (response.isSuccessful) {
                            Log.d(TAG, "âœ… Saved FCM token sent to backend successfully")
                            // Mark token as sent successfully
                            fcmPrefs.edit().putBoolean("fcm_token_sent", true).apply()
                            
                            response.body?.string()?.let { responseBody ->
                                Log.d(TAG, "ðŸš€ Backend response for saved FCM token: $responseBody")
                            }
                        } else {
                            Log.e(TAG, "âŒ Backend returned error for saved FCM token: ${response.code}")
                        }
                        response.close()
                    }
                })
                
            } else {
                Log.w(TAG, "âš ï¸ No saved FCM token found to send")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "âŒ Exception sending saved FCM token: ${e.message}")
        }
    }
}




