package com.renew.jss.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import com.google.firebase.messaging.FirebaseMessaging
import com.renew.jss.ApiConfig
import com.renew.jss.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URL
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.HttpsURLConnection

/**
 * 🪟 OVERLAY LOCK MANAGER — flicker-free kiosk lock.
 *
 * WHY: The original lock is an Activity (KioskActivity). Pressing HOME backgrounds
 * that Activity, so the launcher becomes visible for the ~1s it takes an enforcement
 * path to relaunch it — a visible gap and a security hole.
 *
 * This manager draws the SAME lock UI (kiosk_layout.xml) as a system overlay window
 * (TYPE_APPLICATION_OVERLAY) that sits ABOVE the launcher and every other app. HOME /
 * Recents can no longer expose the launcher because the overlay is always on top and
 * is never backgrounded — there is nothing to relaunch and nothing to flicker.
 *
 * KioskActivity is intentionally KEPT as a fallback for the cases an app overlay can't
 * cover (no SYSTEM_ALERT_WINDOW permission, or the secure keyguard where overlays are
 * hidden). Callers use: `if (!OverlayLockManager.show(ctx)) { <launch KioskActivity> }`.
 */
object OverlayLockManager {

    private const val TAG = "OverlayLockManager"

    // Must match the constants used by KioskActivity's PIN flow — do NOT change.
    private const val PIN_MASTER_KEY = "AuthEghrigwsibfBDsfrrgujrtnbi"

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    @Volatile
    private var overlayView: View? = null

    // UI references for the currently shown overlay
    private var userNameText: TextView? = null
    private var userPhoneText: TextView? = null
    private var userAddressText: TextView? = null
    private var paymentPhotoImage: ImageView? = null
    private var paymentPhotoPlaceholder: TextView? = null
    private var pinBoxes: Array<EditText>? = null
    private var unlockButton: Button? = null

    fun isShown(): Boolean = overlayView != null

    /**
     * Show the overlay lock. Returns true if the overlay is (now) on screen, false if it
     * could not be shown (no overlay permission / unsupported) so the caller can fall back
     * to launching KioskActivity. Safe to call repeatedly and from any thread.
     */
    fun show(context: Context): Boolean {
        val appContext = context.applicationContext

        // TYPE_APPLICATION_OVERLAY needs the "draw over other apps" permission. Without it
        // we cannot add the window, so report failure and let the caller use KioskActivity.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(appContext)) {
            Log.w(TAG, "⚠️ Overlay permission not granted — falling back to KioskActivity")
            return false
        }

        if (isShown()) {
            // Already up; just keep it on top and refresh immersive state.
            mainHandler.post { reassertImmersive() }
            return true
        }

        // WindowManager operations must run on the main thread.
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return addOverlay(appContext)
        }

        // Called from a background thread (service/worker/receiver): schedule on main.
        // We optimistically return true because the permission check above passed; if the
        // add fails on the main thread it logs and the periodic enforcement retries.
        mainHandler.post { addOverlay(appContext) }
        return true
    }

    /** Remove the overlay lock if present. Safe from any thread. */
    fun hide() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            removeOverlay()
        } else {
            mainHandler.post { removeOverlay() }
        }
    }

    @SuppressLint("InflateParams", "ClickableViewAccessibility")
    private fun addOverlay(context: Context): Boolean {
        if (isShown()) return true
        return try {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

            // Wrap the kiosk layout in a FrameLayout so we can overlay the refresh button
            // exactly like KioskActivity does over android.R.id.content.
            val container = FrameLayout(context)
            container.isFocusable = true
            container.isFocusableInTouchMode = true
            container.setBackgroundColor(Color.BLACK) // guarantees no see-through gap

            val inflater = LayoutInflater.from(context)
            val kioskView = inflater.inflate(R.layout.kiosk_layout, container, false)
            container.addView(
                kioskView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )

            bindViews(kioskView)
            setupOtpInputs(context)
            setupPinUnlock(context)
            setupRefreshButton(context, container)

            // Block the hardware BACK key (HOME can't be intercepted from an overlay, but
            // the overlay stays on top regardless, so HOME can't reveal the launcher).
            container.setOnKeyListener { _, keyCode, _ ->
                keyCode == KeyEvent.KEYCODE_BACK
            }

            applyImmersive(container)

            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                type,
                // Focusable (no FLAG_NOT_FOCUSABLE) so the PIN keyboard works. Cover the
                // whole screen incl. status/nav areas and keep the screen awake.
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.OPAQUE
            )
            params.gravity = Gravity.TOP or Gravity.START
            // PAN so a focused PIN box / the unlock button stays visible above the keyboard.
            params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
            // Draw into the status-bar / notch (display cutout) area so the lock covers the
            // whole screen edge-to-edge — otherwise a strip is left uncovered at the top.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                params.layoutInDisplayCutoutMode =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                    } else {
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                    }
            }

            // Don't inset the layout below the status bar — its blue background must fill
            // right to the top edge (combined with the cutout mode above).
            container.fitsSystemWindows = false

            wm.addView(container, params)
            overlayView = container

            // Re-assert immersive once the view is attached (the modern
            // WindowInsetsController is only available on an attached window). A short
            // delay lets the window settle so OEMs honour the hide request.
            mainHandler.post { reassertImmersive() }
            mainHandler.postDelayed({ reassertImmersive() }, 400L)

            // Populate content (cached first, then a background refresh) — same as KioskActivity.
            showCachedUserDetails(context)
            fetchUserDetailsInBackground(context)

            Log.d(TAG, "✅ Overlay lock shown (TYPE_APPLICATION_OVERLAY)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to add overlay: ${e.message}", e)
            overlayView = null
            false
        }
    }

    private fun removeOverlay() {
        val view = overlayView ?: return
        try {
            val wm = view.context.applicationContext
                .getSystemService(Context.WINDOW_SERVICE) as WindowManager
            wm.removeViewImmediate(view)
            Log.d(TAG, "🔓 Overlay lock removed")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to remove overlay: ${e.message}", e)
        } finally {
            overlayView = null
            userNameText = null
            userPhoneText = null
            userAddressText = null
            paymentPhotoImage = null
            paymentPhotoPlaceholder = null
            pinBoxes = null
            unlockButton = null
        }
    }

    private fun bindViews(root: View) {
        userNameText = root.findViewById(R.id.user_name)
        userPhoneText = root.findViewById(R.id.user_phone)
        userAddressText = root.findViewById(R.id.user_address)
        paymentPhotoImage = root.findViewById(R.id.payment_photo)
        paymentPhotoPlaceholder = root.findViewById(R.id.payment_photo_placeholder)
        pinBoxes = arrayOf(
            root.findViewById(R.id.pin_box_1),
            root.findViewById(R.id.pin_box_2),
            root.findViewById(R.id.pin_box_3),
            root.findViewById(R.id.pin_box_4),
            root.findViewById(R.id.pin_box_5),
            root.findViewById(R.id.pin_box_6)
        )
        unlockButton = root.findViewById(R.id.unlock_button)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun reassertImmersive() {
        overlayView?.let { applyImmersive(it) }
    }

    @Suppress("DEPRECATION")
    private fun applyImmersive(view: View) {
        try {
            // Legacy immersive-sticky (works pre-30 and as a fallback everywhere).
            view.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                )
            // Modern controller (API 30+) — more reliable on Samsung/OEM builds. Only
            // available once the view is attached to its window, hence the post-attach
            // re-assert in addOverlay().
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                view.windowInsetsController?.let { controller ->
                    controller.systemBarsBehavior =
                        android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    controller.hide(android.view.WindowInsets.Type.systemBars())
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to apply immersive: ${e.message}")
        }
    }

    // ---------------------------------------------------------------------------------------
    // The logic below mirrors KioskActivity so the overlay behaves identically. Kept
    // self-contained on purpose: KioskActivity remains an untouched, proven fallback.
    // ---------------------------------------------------------------------------------------

    private fun showCachedUserDetails(context: Context) {
        try {
            val prefs = context.getSharedPreferences("kiosk_user_data", Context.MODE_PRIVATE)
            val cachedName = prefs.getString("user_name", null)
            val cachedPhone = prefs.getString("user_phone", null)
            val cachedAddress = prefs.getString("user_address", null)

            if (cachedName != null) {
                userNameText?.text = cachedName
                userPhoneText?.text = cachedPhone ?: "N/A"
                userAddressText?.text = cachedAddress ?: "N/A"
                paymentPhotoImage?.visibility = View.GONE
            } else {
                userNameText?.text = "Device Locked"
                userPhoneText?.text = "Contact Support"
                userAddressText?.text = "Please contact retailer"
                paymentPhotoImage?.visibility = View.GONE
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error showing cached details: ${e.message}")
            userNameText?.text = "Device Locked"
            userPhoneText?.text = "Contact Support"
            userAddressText?.text = "Please contact retailer"
            paymentPhotoImage?.visibility = View.GONE
        }
    }

    private fun fetchUserDetailsInBackground(context: Context) {
        scope.launch {
            try {
                val imei = getDeviceImei(context)
                if (imei.isEmpty()) return@launch

                val userData = fetchLockedDeviceDetails(imei)
                if (userData != null) {
                    cacheUserDetails(context, userData)
                    if (!isShown()) return@launch
                    userNameText?.text = userData.optString("name", "N/A")
                    userPhoneText?.text = userData.optString("phone", "N/A")
                    userAddressText?.text = userData.optString("address", "N/A")
                    paymentPhotoImage?.visibility = View.GONE
                    loadPaymentImage(context, userData.optString("payment_photo").takeIf { it.isNotEmpty() })
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Background fetch failed: ${e.message}")
            }
        }
    }

    private fun cacheUserDetails(context: Context, userData: JSONObject) {
        try {
            val prefs = context.getSharedPreferences("kiosk_user_data", Context.MODE_PRIVATE)
            prefs.edit().apply {
                putString("user_name", userData.optString("name"))
                putString("user_phone", userData.optString("phone"))
                putString("user_address", userData.optString("address"))
                putString("user_id", userData.optInt("user_id").toString())
                apply()
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to cache user details: ${e.message}")
        }
    }

    private fun getDeviceImei(context: Context): String {
        return try {
            val prefs = context.getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)
            val imei1 = prefs.getString("flutter.user_imei1", null)
            val imei2 = prefs.getString("flutter.user_imei2", null)
            when {
                !imei1.isNullOrEmpty() -> imei1
                !imei2.isNullOrEmpty() -> imei2
                else -> ""
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to get saved IMEI: ${e.message}")
            ""
        }
    }

    private suspend fun fetchLockedDeviceDetails(imei: String): JSONObject? = withContext(Dispatchers.IO) {
        try {
            val url = URL(ApiConfig.Api.LOCKED_DEVICE_DESC)
            val connection = url.openConnection() as HttpsURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.connectTimeout = 8000
            connection.readTimeout = 8000
            connection.doOutput = true

            val payload = JSONObject().apply { put("imei", imei) }
            connection.outputStream.use { it.write(payload.toString().toByteArray()) }

            if (connection.responseCode == HttpsURLConnection.HTTP_OK) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val jsonResponse = JSONObject(response)
                if (jsonResponse.optString("status") == "success") {
                    return@withContext jsonResponse.getJSONObject("data")
                }
            }
            null
        } catch (e: Exception) {
            Log.e(TAG, "API call failed", e)
            null
        }
    }

    private fun loadPaymentImage(context: Context, photoPath: String?) {
        val placeholder = paymentPhotoPlaceholder
        val image = paymentPhotoImage
        if (photoPath.isNullOrEmpty()) {
            image?.visibility = View.GONE
            placeholder?.text = "No QR Code Available"
            return
        }
        placeholder?.text = "Loading QR Code..."
        image?.visibility = View.GONE

        scope.launch {
            try {
                val fullUrl = "${ApiConfig.getBaseDomain()}$photoPath"
                val bitmap: Bitmap? = withContext(Dispatchers.IO) {
                    val url = URL(fullUrl)
                    val connection = url.openConnection() as HttpsURLConnection
                    connection.requestMethod = "GET"
                    connection.connectTimeout = 10000
                    connection.readTimeout = 10000
                    if (connection.responseCode == HttpsURLConnection.HTTP_OK) {
                        BitmapFactory.decodeStream(connection.inputStream)
                    } else {
                        null
                    }
                }
                if (!isShown()) return@launch
                if (bitmap != null) {
                    image?.setImageBitmap(bitmap)
                    image?.visibility = View.VISIBLE
                    placeholder?.visibility = View.GONE
                } else {
                    image?.visibility = View.GONE
                    placeholder?.visibility = View.VISIBLE
                    placeholder?.text = "QR Code Unavailable"
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error loading payment photo: ${e.message}")
                image?.visibility = View.GONE
                placeholder?.visibility = View.VISIBLE
                placeholder?.text = "QR Code Error"
            }
        }
    }

    private fun setupOtpInputs(context: Context) {
        val boxes = pinBoxes ?: return
        for (i in boxes.indices) {
            val currentBox = boxes[i]
            currentBox.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    if (s?.length == 1 && i < boxes.size - 1) {
                        boxes[i + 1].requestFocus()
                    }
                }
                override fun afterTextChanged(s: Editable?) {}
            })
            currentBox.setOnClickListener {
                it.requestFocus()
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(it, InputMethodManager.SHOW_IMPLICIT)
            }
            currentBox.setOnFocusChangeListener { v, hasFocus ->
                if (hasFocus) {
                    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                    imm.showSoftInput(v, InputMethodManager.SHOW_IMPLICIT)
                }
            }
            currentBox.setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_DEL && event.action == KeyEvent.ACTION_DOWN) {
                    if (currentBox.text.isEmpty() && i > 0) {
                        boxes[i - 1].requestFocus()
                        boxes[i - 1].text.clear()
                    }
                }
                false
            }
        }
    }

    private fun setupPinUnlock(context: Context) {
        val boxes = pinBoxes ?: return
        unlockButton?.setOnClickListener {
            val enteredPin = boxes.joinToString("") { it.text.toString() }
            if (enteredPin.length != 6) {
                Toast.makeText(context, "Please enter 6-digit PIN", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val deviceImei = getDeviceImei(context)
            if (deviceImei.isEmpty()) {
                Toast.makeText(context, "❌ Device error - Cannot verify PIN", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val generatedPin = generateCode(PIN_MASTER_KEY, deviceImei)
            if (enteredPin == generatedPin) {
                Toast.makeText(context, "✅ PIN Verified - Unlocking device...", Toast.LENGTH_SHORT).show()
                try {
                    com.renew.jss.storage.LockedStateStore.setLocked(context, false)
                    com.renew.jss.storage.KioskStateManager.setKioskEnabled(context, false)
                    com.renew.jss.policy.PolicyDispatcher.apply(context, "KIOSK", false)
                    com.renew.jss.policy.PolicyChangeProcessor.getInstance(context)
                        .updatePersistedPolicy("lock_device", false)
                    Log.d(TAG, "🎉 Overlay PIN unlock completed successfully")
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Overlay PIN unlock failed: ${e.message}")
                }
                hide()
            } else {
                Toast.makeText(context, "❌ Invalid PIN", Toast.LENGTH_SHORT).show()
                boxes.forEach { it.text.clear() }
                boxes[0].requestFocus()
            }
        }
    }

    private fun getIndianTimeKey(): String {
        val istZone = ZoneId.of("Asia/Kolkata")
        val nowIst = ZonedDateTime.ofInstant(Instant.now(), istZone)
        val slot = nowIst.minute / 10
        return "${nowIst.hour}$slot"
    }

    private fun bytesToHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    private fun generateCode(masterKey: String, keyword: String): String {
        val timeKey = getIndianTimeKey()
        val data = "$masterKey:$keyword:$timeKey"
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(masterKey.toByteArray(), "HmacSHA256"))
        val hashBytes = mac.doFinal(data.toByteArray())
        val first8 = bytesToHex(hashBytes).substring(0, 8)
        val num = first8.toLong(16)
        return "%06d".format(num % 1_000_000)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupRefreshButton(context: Context, root: FrameLayout) {
        try {
            val buttonContainer = FrameLayout(context).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.END
                    val margin = (16 * resources.displayMetrics.density).toInt()
                    topMargin = margin
                    rightMargin = margin
                    marginEnd = margin
                }
                isClickable = true
                isFocusable = true
            }
            val backgroundDrawable = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#40000000"))
            }
            val imageView = ImageView(context).apply {
                val size = (40 * resources.displayMetrics.density).toInt()
                layoutParams = FrameLayout.LayoutParams(size, size)
                val padding = (8 * resources.displayMetrics.density).toInt()
                setPadding(padding, padding, padding, padding)
                background = backgroundDrawable
                setImageResource(android.R.drawable.ic_popup_sync)
                setColorFilter(Color.WHITE)
            }
            buttonContainer.addView(imageView)
            buttonContainer.setOnClickListener { triggerFcmTokenRefresh(context) }
            root.addView(buttonContainer)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to setup refresh button: ${e.message}")
        }
    }

    private fun triggerFcmTokenRefresh(context: Context) {
        Toast.makeText(context, "Refreshing connection...", Toast.LENGTH_SHORT).show()
        try {
            FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                if (!task.isSuccessful) {
                    Toast.makeText(context, "Refresh failed: ${task.exception?.message}", Toast.LENGTH_LONG).show()
                    return@addOnCompleteListener
                }
                val token = task.result
                if (token.isNullOrEmpty()) {
                    Toast.makeText(context, "Refresh failed: Empty Token", Toast.LENGTH_SHORT).show()
                    return@addOnCompleteListener
                }
                context.getSharedPreferences("fcm_prefs", Context.MODE_PRIVATE)
                    .edit().putString("fcm_token", token).apply()
                sendSavedFcmTokenToBackend(context, token)
            }
        } catch (e: Exception) {
            Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun sendSavedFcmTokenToBackend(context: Context, token: String) {
        scope.launch {
            try {
                val imei = getDeviceImei(context)
                if (imei.isEmpty()) {
                    Toast.makeText(context, "IMEI not available", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                val fcmPrefs = context.getSharedPreferences("fcm_prefs", Context.MODE_PRIVATE)
                val isTokenSent = fcmPrefs.getBoolean("fcm_token_sent", false)
                val json = JSONObject().apply {
                    put("imei", imei)
                    put("fcmToken", token)
                    put("first_time", !isTokenSent)
                }
                val requestBody = json.toString()
                    .toRequestBody("application/json; charset=utf-8".toMediaType())
                val request = Request.Builder()
                    .url(ApiConfig.FCM_TOKEN_ENDPOINT)
                    .post(requestBody)
                    .build()
                val client = OkHttpClient.Builder()
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(15, TimeUnit.SECONDS)
                    .build()
                withContext(Dispatchers.IO) {
                    try {
                        val response = client.newCall(request).execute()
                        val ok = response.isSuccessful
                        val code = response.code
                        response.close()
                        withContext(Dispatchers.Main) {
                            if (ok) {
                                fcmPrefs.edit().putBoolean("fcm_token_sent", true).apply()
                                Toast.makeText(context, "Connection updated successfully", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "Server error: $code", Toast.LENGTH_SHORT).show()
                            }
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "Network error: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
