package com.renew.jss

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.os.UserHandle

class DeviceOwnerReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        // Initialize and setup device admin when enabled
        try {
            com.renew.jss.device.DeviceController.init(context)
            com.renew.jss.device.DeviceController.setup(context)
            android.util.Log.i("DeviceOwnerReceiver", "Device admin setup completed on enable")
        } catch (e: Exception) {
            android.util.Log.e("DeviceOwnerReceiver", "Failed to setup device admin on enable: ${e.message}")
        }
        
        // Launch MainActivity to start policy monitoring service safely
        try {
            val activityIntent = Intent(context, com.renew.jss.activity.MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                putExtra("START_POLICY_SERVICE", true)
            }
            context.startActivity(activityIntent)
            android.util.Log.i("DeviceOwnerReceiver", "MainActivity launched to start PolicyMonitoringService")
        } catch (e: Exception) {
            android.util.Log.e("DeviceOwnerReceiver", "Failed to launch MainActivity: ${e.message}")
        }
    }

    override fun onDisabled(context: Context, intent: Intent) {
        // no-op
    }

    override fun onDisableRequested(
        context: Context,
        intent: Intent
    ): CharSequence? {
        return super.onDisableRequested(context, intent)
    }

    override fun onPasswordFailed(
        context: Context,
        intent: Intent,
        user: UserHandle
    ) {
        // no-op
    }
}
