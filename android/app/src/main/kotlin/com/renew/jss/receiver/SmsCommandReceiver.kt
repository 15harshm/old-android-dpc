package com.renew.jss.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import com.renew.jss.device.DeviceController
import com.renew.jss.storage.AuthorizedNumbersStore

class SmsCommandReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        Log.d("SmsCommandReceiver", "?? SMS received - intent: ${intent?.action}")
        
        if (
            context == null ||
            intent == null ||
            intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION
        ) {
            Log.d("SmsCommandReceiver", "? Invalid SMS intent or action")
            return
        }

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        Log.d("SmsCommandReceiver", "?? Processing ${messages.size} SMS messages")

        for (sms in messages) {
            val senderRaw = sms.originatingAddress ?: continue
            val body = sms.messageBody?.trim() ?: continue

            Log.d("SmsCommandReceiver", "?? SMS from: $senderRaw")
            Log.d("SmsCommandReceiver", "?? Message body: '$body'")

            val sender = normalize(senderRaw)
            Log.d("SmsCommandReceiver", "?? Normalized sender: '$sender'")

            // Debug: Show all authorized numbers
            val allowedNumbers = AuthorizedNumbersStore.getNumbers(context)
            Log.d("SmsCommandReceiver", "?? Authorized numbers: $allowedNumbers")

            if (!isAuthorizedSender(context, sender)) {
                Log.d("SmsCommandReceiver", "?? Sender NOT authorized: $sender")
                continue
            }

            Log.d("SmsCommandReceiver", "? Sender authorized: $sender")

            when {
                // New unlock message format
                body.equals("Your OTP / Password is Unlock the device for the this application. Thank You Enjoy JSSINF", true) -> {
                    Log.d("SmsCommandReceiver", "?? UNLOCK command detected!")
                    Log.d("SmsCommandReceiver", "?? About to call DeviceController.unlockDevice()")
                    DeviceController.unlockDevice(context)
                    Log.d("SmsCommandReceiver", "?? DeviceController.unlockDevice() completed")
                    abortBroadcast()
                }

                // New lock message format
                body.equals("Your OTP / Password is Lock the device for the this application. Thank You Enjoy JSSINF", true) -> {
                    Log.d("SmsCommandReceiver", "?? LOCK command detected!")
                    Log.d("SmsCommandReceiver", "?? About to call DeviceController.lockDevice()")
                    DeviceController.lockDevice(context)
                    Log.d("SmsCommandReceiver", "?? DeviceController.lockDevice() completed")
                    abortBroadcast()
                }
                
                else -> {
                    Log.d("SmsCommandReceiver", "? Unknown message format: '$body'")
                }
            }
        }
    }

    private fun isAuthorizedSender(context: Context, sender: String): Boolean {
        val allowedNumbers = AuthorizedNumbersStore.getNumbers(context)
        Log.d("SmsCommandReceiver", "?? Checking authorization for sender: '$sender'")
        Log.d("SmsCommandReceiver", "?? Available authorized numbers: $allowedNumbers")

        val isAuthorized = allowedNumbers.any { allowed ->
            val normalizedAllowed = normalize(allowed)
            
            // Special handling for JSSINF pattern - match any sender containing JSSINF
            val isMatch = if (normalizedAllowed == "JSSINF") {
                sender.contains("JSSINF", ignoreCase = true)
            } else {
                normalizedAllowed == sender
            }
            
            Log.d("SmsCommandReceiver", "?? Comparing: '$normalizedAllowed' == '$sender'? $isMatch")
            isMatch
        }
        
        Log.d("SmsCommandReceiver", "?? Authorization result: $isAuthorized")
        return isAuthorized
    }

    private fun normalize(number: String): String {
        val original = number
        val result = if (number.contains("JSSINF", ignoreCase = true)) {
            // For alphanumeric senders containing JSSINF, keep original format and uppercase
            number.trim().uppercase()
        } else {
            // Keep only digits & last 10 digits (India-safe) for phone numbers
            val digits = number.filter { it.isDigit() }
            if (digits.length > 10) {
                digits.takeLast(10)
            } else {
                digits
            }
        }
        Log.d("SmsCommandReceiver", "?? Normalize: '$original' -> '$result'")
        return result
    }
}




