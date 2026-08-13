package com.renew.jss.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class UninstallResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // 🎯 MATCH CODEWINT: Handle uninstall result exactly like them
        val status = intent?.getIntExtra("android.content.pm.extra.STATUS", -1)
        val message = intent?.getStringExtra("android.content.pm.extra.STATUS_MESSAGE")
        
        println("App uninstall status: $status and message: $message")
        android.util.Log.i("UninstallResultReceiver", "App uninstall status: $status and message: $message")
    }
}
