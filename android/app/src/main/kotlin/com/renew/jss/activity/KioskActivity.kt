package com.renew.jss.activity

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.PowerManager
import android.util.Base64
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.EditText
import android.widget.Button
import android.widget.Toast
import android.view.inputmethod.InputMethodManager
import android.text.TextWatcher
import android.text.Editable
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.addCallback
import androidx.core.app.NotificationCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.renew.jss.DeviceOwnerReceiver
import com.renew.jss.R
import com.renew.jss.ApiConfig
import com.renew.jss.storage.KioskStateManager
import com.renew.jss.storage.LockedStateStore
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import com.google.firebase.messaging.FirebaseMessaging
import java.io.IOException
import java.util.concurrent.TimeUnit

class KioskActivity : ComponentActivity() {

    private var isOtherAppLaunched = false
    private var isLockScreenVisible = false
    
    // UI elements for user details
    private lateinit var userNameText: TextView
    private lateinit var userPhoneText: TextView
    private lateinit var userAddressText: TextView
    private lateinit var paymentPhotoImage: ImageView
    private lateinit var paymentPhotoPlaceholder: TextView
    
    // UI elements for PIN unlock
    private lateinit var pinBoxes: Array<EditText>
    private lateinit var unlockButton: Button
    
    private val activityScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    companion object {
        private const val TAG = "KioskActivity:"
        var isLockScreenVisibleStatic = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        Log.d(TAG, "🚀 KioskActivity.onCreate() called")
        
        // 🎯 SHOW KIOSK UI: Set the content view FIRST
        setContentView(R.layout.kiosk_layout)
        
        // Initialize UI elements
        userNameText = findViewById(R.id.user_name)
        userPhoneText = findViewById(R.id.user_phone)
        userAddressText = findViewById(R.id.user_address)
        paymentPhotoImage = findViewById(R.id.payment_photo)
        paymentPhotoPlaceholder = findViewById(R.id.payment_photo_placeholder)
        
        // Initialize PIN unlock UI elements
        pinBoxes = arrayOf(
            findViewById(R.id.pin_box_1),
            findViewById(R.id.pin_box_2),
            findViewById(R.id.pin_box_3),
            findViewById(R.id.pin_box_4),
            findViewById(R.id.pin_box_5),
            findViewById(R.id.pin_box_6)
        )
        unlockButton = findViewById(R.id.unlock_button)
        
        setupOtpInputs()
        setupPinUnlock()
        setupRefreshButton()
        
        // 🎯 FIX: Suppress enter transition animation to prevent black/white flash
        overridePendingTransition(0, 0)
        
        // 🎯 CODEWINT APPROACH: Enable immersive mode AFTER setContentView
        enableImmersiveMode()
        
        // 🎯 CODEWINT APPROACH: Block back button completely
        onBackPressedDispatcher.addCallback(object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Do nothing - block back button completely
                Log.d(TAG, "🛑 Back button blocked - doing nothing")
            }
        })
        
        // Set lock screen visible
        isLockScreenVisible = true
        isLockScreenVisibleStatic = true
        
        // 🔒 CRITICAL: Show cached/offline data first, then fetch fresh data
        showCachedUserDetails()
        
        // Fetch user details in background (non-blocking)
        fetchUserDetailsInBackground()
        
        Log.d(TAG, "✅ KioskActivity created successfully")
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "🔄 KioskActivity.onResume() called")
        
        isOtherAppLaunched = false
        isLockScreenVisible = true
        isLockScreenVisibleStatic = true
        
        // 🎯 CODEWINT APPROACH: Check if device is locked, if not, finish
        if (!KioskStateManager.isKioskEnabled(this) && !LockedStateStore.isLocked(this)) {
            Log.d(TAG, "🔓 Device is not locked - finishing activity")
            finish()
            return
        }
        
        // 🎯 FIX: Suppress re-enter animation to prevent flash when activity comes to front
        overridePendingTransition(0, 0)
        
        // 🎯 CODEWINT APPROACH: Re-enable immersive mode
        enableImmersiveMode()
        
        Log.d(TAG, "✅ KioskActivity resumed - device is locked")
    }

    override fun onPause() {
        super.onPause()
        Log.d(TAG, "⏸️ KioskActivity.onPause() called")
        
        isLockScreenVisible = false
        isLockScreenVisibleStatic = false
        
        // 🚀 REMOVED: Redundant restart here causes flickering.
        // Enforcement is handled by MyAccessibilityService and KioskEnforcementService.
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        Log.d(TAG, "📥 KioskActivity.onNewIntent() called")
        
        // 🎯 CODEWINT APPROACH: Handle EXIT_KIOSK intent
        if (intent != null && intent.getBooleanExtra("EXIT_KIOSK", false)) {
            Log.d(TAG, "🔓 Received EXIT_KIOSK signal - finishing activity")
            finish()
            return
        }
        
        // Re-enable immersive mode
        enableImmersiveMode()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        Log.d(TAG, "🔑 Key pressed: $keyCode")
        
        // 🎯 CODEWINT APPROACH: Block specific keys
        when (keyCode) {
            KeyEvent.KEYCODE_BACK -> {
                Log.d(TAG, "🛑 Back key blocked")
                return true // Block back key
            }
            KeyEvent.KEYCODE_HOME -> {
                Log.d(TAG, "🛑 Home key blocked")
                return true // Block home key
            }
            KeyEvent.KEYCODE_APP_SWITCH -> {
                Log.d(TAG, "🛑 Recent apps key blocked")
                return true // Block recent apps
            }
            KeyEvent.KEYCODE_MENU -> {
                Log.d(TAG, "🛑 Menu key blocked")
                return true // Block menu
            }
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN -> {
                Log.d(TAG, "🛑 Volume key blocked")
                return true // Block volume
            }
        }
        
        return super.onKeyDown(keyCode, event)
    }

    private fun enableImmersiveMode() {
        Log.d(TAG, "🎯 Enabling immersive mode")
        
        try {
            // 🎯 CODEWINT APPROACH: Complete immersive mode
            WindowCompat.setDecorFitsSystemWindows(window, false)
            
            val insetsController = WindowCompat.getInsetsController(window, window.decorView)
            insetsController.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE)
            insetsController.hide(WindowInsetsCompat.Type.systemBars())
            
            // 🎯 CODEWINT APPROACH: Multiple window flags for complete lock
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
            window.addFlags(WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD)
            window.addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            
            Log.d(TAG, "✅ Immersive mode enabled successfully")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to enable immersive mode: ${e.message}", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "💀 KioskActivity.onDestroy() called")
        isLockScreenVisible = false
        isLockScreenVisibleStatic = false
        activityScope.cancel()
    }
    
    /**
     * 🔒 SHOW CACHED USER DETAILS (Offline-first)
     * Ensures kiosk works immediately without network dependency
     */
    private fun showCachedUserDetails() {
        try {
            val prefs = getSharedPreferences("kiosk_user_data", Context.MODE_PRIVATE)
            
            // Show cached data if available
            val cachedName = prefs.getString("user_name", null)
            val cachedPhone = prefs.getString("user_phone", null)
            val cachedAddress = prefs.getString("user_address", null)
            
            if (cachedName != null) {
                userNameText.text = cachedName
                userPhoneText.text = cachedPhone ?: "N/A"
                userAddressText.text = cachedAddress ?: "N/A"
                paymentPhotoImage.visibility = View.GONE
                
                Log.d(TAG, "✅ Showing cached user details")
            } else {
                // Show default locked state
                userNameText.text = "Device Locked"
                userPhoneText.text = "Contact Support"
                userAddressText.text = "Please contact retailer"
                paymentPhotoImage.visibility = View.GONE
                
                Log.d(TAG, "📱 Showing default lock state (no cached data)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error showing cached details: ${e.message}")
            
            // Fallback to basic lock state
            userNameText.text = "Device Locked"
            userPhoneText.text = "Contact Support"
            userAddressText.text = "Please contact retailer"
            paymentPhotoImage.visibility = View.GONE
        }
    }
    
    /**
     * 🔄 FETCH USER DETAILS IN BACKGROUND (Non-blocking)
     * Updates UI when network is available, but doesn't block kiosk functionality
     */
    private fun fetchUserDetailsInBackground() {
        activityScope.launch {
            try {
                // Get actual device IMEI
                val imei = getDeviceImei()
                
                if (imei.isEmpty()) {
                    Log.e(TAG, "No IMEI available")
                    return@launch
                }
                
                val userData = fetchLockedDeviceDetails(imei)
                
                if (userData != null) {
                    // Cache the user data for offline use
                    cacheUserDetails(userData)
                    
                    // Update UI with fresh data
                    userNameText.text = userData.getString("name") ?: "N/A"
                    userPhoneText.text = userData.getString("phone") ?: "N/A"
                    userAddressText.text = userData.getString("address") ?: "N/A"
                    paymentPhotoImage.visibility = View.GONE
                    
                    // Load payment photo if available
                    loadPaymentImage(userData.getString("payment_photo")?.takeIf { it.isNotEmpty() })
                    
                    Log.d(TAG, "✅ User details updated from network")
                } else {
                    Log.w(TAG, "⚠️ Network fetch failed, using cached/default data")
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Background fetch failed: ${e.message}")
                // Don't update UI - keep showing cached/default data
            }
        }
    }
    
    /**
     * 💾 CACHE USER DETAILS FOR OFFLINE USE
     */
    private fun cacheUserDetails(userData: JSONObject) {
        try {
            val prefs = getSharedPreferences("kiosk_user_data", Context.MODE_PRIVATE)
            prefs.edit().apply {
                putString("user_name", userData.getString("name"))
                putString("user_phone", userData.getString("phone"))
                putString("user_address", userData.getString("address"))
                putString("user_id", userData.getInt("user_id").toString())
                apply()
            }
            Log.d(TAG, "💾 User details cached for offline use")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to cache user details: ${e.message}")
        }
    }
    
    /**
     * Get device IMEI from saved preferences
     */
    private fun getDeviceImei(): String {
        return try {
            // Get IMEI from Flutter SharedPreferences
            val prefs = getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)
            val imei1 = prefs.getString("flutter.user_imei1", null)
            val imei2 = prefs.getString("flutter.user_imei2", null)
            
            val imei = if (!imei1.isNullOrEmpty()) {
                imei1
            } else if (!imei2.isNullOrEmpty()) {
                imei2
            } else {
                null
            }
            
            if (!imei.isNullOrEmpty()) {
                Log.d(TAG, "✅ Using saved IMEI: $imei")
                imei
            } else {
                Log.e(TAG, "❌ No saved IMEI found")
                ""
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to get saved IMEI: ${e.message}")
            ""
        }
    }
    
    /**
     * Fetch locked device details from API
     */
    private suspend fun fetchLockedDeviceDetails(imei: String): JSONObject? = withContext(Dispatchers.IO) {
        try {
            val url = URL(ApiConfig.Api.LOCKED_DEVICE_DESC)
            val connection = url.openConnection() as HttpsURLConnection
            
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.connectTimeout = 8000  // ✅ Prevent infinite block → ANR
            connection.readTimeout = 8000     // ✅ Prevent infinite block → ANR
            connection.doOutput = true
            
            // Create request payload
            val payload = JSONObject().apply {
                put("imei", imei)
            }
            
            // Send request
            connection.outputStream.use { output ->
                output.write(payload.toString().toByteArray())
            }
            
            val responseCode = connection.responseCode
            if (responseCode == HttpsURLConnection.HTTP_OK) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val jsonResponse = JSONObject(response)
                
                if (jsonResponse.getString("status") == "success") {
                    return@withContext jsonResponse.getJSONObject("data")
                }
            }
            
            return@withContext null
        } catch (e: Exception) {
            Log.e(TAG, "API call failed", e)
            return@withContext null
        }
    }
    
    /**
     * Load payment photo from URL
     */
    private fun loadPaymentImage(photoPath: String?) {
        if (photoPath.isNullOrEmpty()) {
            paymentPhotoImage.visibility = View.GONE
            paymentPhotoPlaceholder.text = "No QR Code Available"
            return
        }
        
        // Show loading state
        paymentPhotoPlaceholder.text = "Loading QR Code..."
        paymentPhotoImage.visibility = View.GONE
        
        activityScope.launch {
            try {
                // Construct full URL
                val fullUrl = "${ApiConfig.getBaseDomain()}$photoPath"
                Log.d(TAG, "Loading payment photo from: $fullUrl")
                
                val bitmap = withContext(Dispatchers.IO) {
                    val url = URL(fullUrl)
                    val connection = url.openConnection() as HttpsURLConnection
                    connection.requestMethod = "GET"
                    connection.connectTimeout = 10000
                    connection.readTimeout = 10000
                    
                    if (connection.responseCode == HttpsURLConnection.HTTP_OK) {
                        val inputStream = connection.inputStream
                        BitmapFactory.decodeStream(inputStream)
                    } else {
                        null
                    }
                }
                
                if (bitmap != null) {
                    paymentPhotoImage.setImageBitmap(bitmap)
                    paymentPhotoImage.visibility = View.VISIBLE
                    paymentPhotoPlaceholder.visibility = View.GONE
                    Log.d(TAG, "✅ Payment photo loaded successfully")
                } else {
                    paymentPhotoImage.visibility = View.GONE
                    paymentPhotoPlaceholder.visibility = View.VISIBLE
                    paymentPhotoPlaceholder.text = "QR Code Unavailable"
                    Log.e(TAG, "❌ Failed to load payment photo")
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error loading payment photo: ${e.message}", e)
                paymentPhotoImage.visibility = View.GONE
                paymentPhotoPlaceholder.visibility = View.VISIBLE
                paymentPhotoPlaceholder.text = "QR Code Error"
            }
        }
    }

    private fun setupOtpInputs() {
        for (i in pinBoxes.indices) {
            val currentBox = pinBoxes[i]
            
            currentBox.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    if (s?.length == 1 && i < pinBoxes.size - 1) {
                        pinBoxes[i + 1].requestFocus()
                    }
                }
                override fun afterTextChanged(s: Editable?) {}
            })
            
            // 🎯 NEW: Show keyboard on click/focus
            currentBox.setOnClickListener {
                it.requestFocus()
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(it, InputMethodManager.SHOW_IMPLICIT)
            }
            
            currentBox.setOnFocusChangeListener { v, hasFocus ->
                if (hasFocus) {
                    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                    imm.showSoftInput(v, InputMethodManager.SHOW_IMPLICIT)
                }
            }
            
            currentBox.setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_DEL && event.action == KeyEvent.ACTION_DOWN) {
                    if (currentBox.text.isEmpty() && i > 0) {
                        pinBoxes[i - 1].requestFocus()
                        pinBoxes[i - 1].text.clear()
                    }
                }
                false
            }
        }
    }

    /**
     * 🔑 SETUP PIN UNLOCK FUNCTIONALITY
     */
    private fun setupPinUnlock() {
        unlockButton.setOnClickListener {
            val enteredPin = pinBoxes.joinToString("") { it.text.toString() }
            
            if (enteredPin.length != 6) {
                Toast.makeText(this, "Please enter 6-digit PIN", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            
            val deviceImei = getDeviceImei()
            if (deviceImei.isNotEmpty()) {
                val generatedPin = generateCode("AuthEghrigwsibfBDsfrrgujrtnbi", deviceImei)
                
                if (enteredPin == generatedPin) {
                    Toast.makeText(this, "✅ PIN Verified - Unlocking device...", Toast.LENGTH_SHORT).show()
                    
                    Log.d(TAG, "pinissue: 🔍 PIN VERIFIED - Starting unlock process")
                    
                    try {
                        // Update storage state to unlocked
                        com.renew.jss.storage.LockedStateStore.setLocked(this@KioskActivity, false)
                        com.renew.jss.storage.KioskStateManager.setKioskEnabled(this@KioskActivity, false)
                        Log.d(TAG, "pinissue: 🔓 Step 1 - Set LockedStateStore and KioskStateManager to false")

                        // Remove Kiosk
                        com.renew.jss.policy.PolicyDispatcher.apply(this@KioskActivity, "KIOSK", false)
                        Log.d(TAG, "pinissue: ✅ Step 2 - KIOSK restriction removed via PolicyDispatcher")

                        // Update local persisted policy so background workers don't lock it again
                        com.renew.jss.policy.PolicyChangeProcessor.getInstance(this@KioskActivity)
                            .updatePersistedPolicy("lock_device", false)
                        Log.d(TAG, "pinissue: ✅ Step 3 - Updated local PolicyChangeProcessor cache")

                        // Local unlock succeeded - no backend notification needed for offline pin unlock
                        Log.d(TAG, "pinissue: ✅ Step 4 - Local unlock state applied")

                        Log.d(TAG, "pinissue: 🎉 PIN UNLOCK PROCESS COMPLETED SUCCESSFULLY")
                    } catch (e: Exception) {
                        Log.e(TAG, "pinissue: ❌ PIN UNLOCK FAILED - Exception: ${e.message}")
                    }
                    
                    finish()
                } else {
                    Toast.makeText(this, "❌ Invalid PIN", Toast.LENGTH_SHORT).show()
                    pinBoxes.forEach { it.text.clear() }
                    pinBoxes[0].requestFocus()
                }
            } else {
                Toast.makeText(this, "❌ Device error - Cannot verify PIN", Toast.LENGTH_SHORT).show()
            }
        }
    }



    private fun getIndianTimeKey(): String {
        val istZone = ZoneId.of("Asia/Kolkata")
        val nowIst = ZonedDateTime.ofInstant(Instant.now(), istZone)
        
        val hour = nowIst.hour
        val minute = nowIst.minute
        
        val slot = minute / 10
        
        return "$hour$slot"
    }
    
    private fun bytesToHex(bytes: ByteArray): String {
        return bytes.joinToString("") { "%02x".format(it) }
    }
    
    private fun generateCode(masterKey: String, keyword: String): String {
        val timeKey = getIndianTimeKey()
        val data = "$masterKey:$keyword:$timeKey"
        
        val mac = Mac.getInstance("HmacSHA256")
        val keySpec = SecretKeySpec(masterKey.toByteArray(), "HmacSHA256")
        mac.init(keySpec)
        
        val hashBytes = mac.doFinal(data.toByteArray())
        val hex = bytesToHex(hashBytes)
        
        val first8 = hex.substring(0, 8)
        val num = first8.toLong(16)
        
        val code = num % 1_000_000
        
        return "%06d".format(code)
    }

    /**
     * 🔄 SETUP REFRESH/SYNC BUTTON
     * Injects a small circular sync button in the top-right corner of the layout.
     */
    private fun setupRefreshButton() {
        try {
            val rootLayout = findViewById<FrameLayout>(android.R.id.content)
            if (rootLayout == null) {
                Log.e(TAG, "❌ Root layout not found, cannot add refresh button")
                return
            }

            // Create a container FrameLayout for padding and click area
            val buttonContainer = FrameLayout(this).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = android.view.Gravity.TOP or android.view.Gravity.END
                    // 16dp margins
                    val margin = (16 * resources.displayMetrics.density).toInt()
                    topMargin = margin
                    rightMargin = margin
                    marginEnd = margin
                }
                isClickable = true
                isFocusable = true
            }

            // Create circular background (dark semi-transparent)
            val backgroundDrawable = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(android.graphics.Color.parseColor("#40000000")) // 25% black
            }

            // Create the actual ImageView
            val imageView = ImageView(this).apply {
                val size = (40 * resources.displayMetrics.density).toInt()
                layoutParams = FrameLayout.LayoutParams(size, size)
                
                // Add padding inside circle
                val padding = (8 * resources.displayMetrics.density).toInt()
                setPadding(padding, padding, padding, padding)
                
                background = backgroundDrawable
                setImageResource(android.R.drawable.ic_popup_sync) // Standard rotate/sync icon
                
                // Set color filter to white for visibility
                setColorFilter(android.graphics.Color.WHITE)
            }

            buttonContainer.addView(imageView)
            
            // Set click listener on container for larger hit target
            buttonContainer.setOnClickListener {
                Log.d(TAG, "🔄 Sync button clicked on lock screen")
                triggerFcmTokenRefresh()
            }

            rootLayout.addView(buttonContainer)
            Log.d(TAG, "✅ Sync button programmatically added to Kiosk screen")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to setup refresh button: ${e.message}", e)
        }
    }

    /**
     * 🔄 TRIGGER FCM TOKEN REFRESH
     * Fetches current FCM token and requests backend upload.
     */
    private fun triggerFcmTokenRefresh() {
        Toast.makeText(this, "Refreshing connection...", Toast.LENGTH_SHORT).show()
        try {
            FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                if (!task.isSuccessful) {
                    Log.w(TAG, "❌ Fetching FCM registration token failed", task.exception)
                    Toast.makeText(this, "Refresh failed: ${task.exception?.message}", Toast.LENGTH_LONG).show()
                    return@addOnCompleteListener
                }

                val token = task.result
                if (token.isNullOrEmpty()) {
                    Toast.makeText(this, "Refresh failed: Empty Token", Toast.LENGTH_SHORT).show()
                    return@addOnCompleteListener
                }

                Log.d(TAG, "FCMPC 🚀 Current FCM token fetched on lock screen: ${token.take(10)}...")

                // Save token locally
                val fcmPrefs = getSharedPreferences("fcm_prefs", Context.MODE_PRIVATE)
                fcmPrefs.edit().putString("fcm_token", token).apply()

                // Send to backend
                sendSavedFcmTokenToBackend(token)
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to refresh FCM token: ${e.message}")
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 🚀 SEND FCM TOKEN TO BACKEND
     * Posts FCM token payload directly to socket endpoint.
     */
    private fun sendSavedFcmTokenToBackend(token: String) {
        activityScope.launch {
            try {
                val imei = getDeviceImei()
                if (imei.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@KioskActivity, "IMEI not available", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                val backendUrl = ApiConfig.FCM_TOKEN_ENDPOINT
                val fcmPrefs = getSharedPreferences("fcm_prefs", Context.MODE_PRIVATE)
                val isTokenSent = fcmPrefs.getBoolean("fcm_token_sent", false)

                val json = JSONObject().apply {
                    put("imei", imei)
                    put("fcmToken", token)
                    put("first_time", !isTokenSent)
                }

                val mediaType = "application/json; charset=utf-8".toMediaType()
                val requestBody = json.toString().toRequestBody(mediaType)

                val request = Request.Builder()
                    .url(backendUrl)
                    .post(requestBody)
                    .build()

                val client = OkHttpClient.Builder()
                    .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                    .build()

                withContext(Dispatchers.IO) {
                    try {
                        val response = client.newCall(request).execute()
                        withContext(Dispatchers.Main) {
                            if (response.isSuccessful) {
                                Log.d(TAG, "✅ FCM token updated successfully via lock screen refresh button")
                                fcmPrefs.edit().putBoolean("fcm_token_sent", true).apply()
                                Toast.makeText(this@KioskActivity, "Connection updated successfully", Toast.LENGTH_SHORT).show()
                            } else {
                                Log.e(TAG, "❌ Backend returned error: ${response.code}")
                                Toast.makeText(this@KioskActivity, "Server error: ${response.code}", Toast.LENGTH_SHORT).show()
                            }
                            response.close()
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Network error updating token: ${e.message}", e)
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@KioskActivity, "Network error: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error in sendSavedFcmTokenToBackend: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@KioskActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}




