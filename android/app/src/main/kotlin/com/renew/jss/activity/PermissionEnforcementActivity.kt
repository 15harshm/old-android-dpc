package com.renew.jss.activity

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import com.renew.jss.DeviceOwnerReceiver
import com.renew.jss.R

class PermissionEnforcementActivity : ComponentActivity() {

    companion object {
        private const val TAG = "PermissionEnforcement"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 🔒 LOCK DOWN: Ensure this activity covers everything
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        window.addFlags(WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD)
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        
        setContentView(R.layout.activity_permissions_enforcement)

        val btnAdmin = findViewById<Button>(R.id.btn_enable_admin)
        val btnAccessibility = findViewById<Button>(R.id.btn_enable_accessibility)
        val txtMessage = findViewById<TextView>(R.id.permission_message)

        btnAdmin.setOnClickListener {
            val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, ComponentName(this, DeviceOwnerReceiver::class.java))
            intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Device Admin is required for security.")
            startActivity(intent)
        }

        btnAccessibility.setOnClickListener {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
        }

        // 🚀 XIAOMI OPTIMIZATION: Show button for Xiaomi/Redmi devices
        val btnXiaomi = findViewById<Button>(R.id.btn_xiaomi_settings)
        if (com.renew.jss.utils.OemOptimizer.isXiaomi()) {
            btnXiaomi.visibility = android.view.View.VISIBLE
            btnXiaomi.setOnClickListener {
                com.renew.jss.utils.OemOptimizer.openXiaomiAutostart(this)
                // Also show a toast or dialog to explain what to do
                android.widget.Toast.makeText(this, "Enable 'Autostart' and 'Battery Optimization (No Restrictions)'", android.widget.Toast.LENGTH_LONG).show()
            }
        }

        // 🚫 BLOCK BACK BUTTON
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                Log.d(TAG, "Back button blocked")
            }
        })
    }

    override fun onResume() {
        super.onResume()
        checkPermissions()
    }

    private fun checkPermissions() {
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(this, DeviceOwnerReceiver::class.java)
        
        val isAdminActive = dpm.isAdminActive(admin)
        val isAccessibilityEnabled = isAccessibilityServiceEnabled(this)

        if (isAdminActive && isAccessibilityEnabled) {
            Log.d(TAG, "✅ All permissions restored. Finishing enforcement.")
            finish()
        }
    }

    private fun isAccessibilityServiceEnabled(context: Context): Boolean {
        // 🛡️ DUAL CHECK: Check Settings.Secure + AccessibilityManager
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? android.view.accessibility.AccessibilityManager
        val isServiceRunning = am?.isEnabled == true && 
            am.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_GENERIC)
              ?.any { it.resolveInfo.serviceInfo.packageName == context.packageName } == true
        
        if (isServiceRunning) return true

        val expectedService = ComponentName(context, com.renew.jss.service.MyAccessibilityService::class.java).flattenToString()
        val enabledServices = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        return enabledServices != null && (enabledServices.contains(expectedService) || enabledServices.contains(context.packageName))
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // Block Volume and Home keys if possible (some require Accessibility, which might be off)
        return when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_BACK -> true
            else -> super.onKeyDown(keyCode, event)
        }
    }
}
