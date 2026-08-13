package com.renew.jss.utils

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.ActivityCompat
import com.renew.jss.DeviceOwnerReceiver
import kotlin.text.Regex

/**
 * 📱 PHONE MANAGER - Handles phone number retrieval from SIM cards
 * 
 * This class retrieves phone numbers from SIM cards using the same approach
 * as competitor1, supporting both single and dual SIM devices.
 */
object PhoneManager {
    
    private const val TAG = "PhoneManager"
    
    /**
     * 📱 Get all available phone numbers from SIM cards
     * Returns a map with "sim1" and "sim2" keys (empty strings if not available)
     */
    fun getPhoneNumbers(context: Context): Map<String, String> {
        Log.d(TAG, "soc-phone 📱 Fetching phone numbers from SIM cards...")
        
        val phoneNumbers = mutableMapOf<String, String>()
        phoneNumbers["sim1"] = "0"
        phoneNumbers["sim2"] = "0"
        
        try {
            // Check permissions first
            if (!hasPhonePermissions(context)) {
                Log.w(TAG, "soc-phone ⚠️ Phone permissions not granted, cannot fetch phone numbers.")
                return phoneNumbers
            }
            
            if (Build.VERSION.SDK_INT >= 33) {
                // Android 13+ approach using SubscriptionManager
                getPhoneNumbersModern(context, phoneNumbers)
            } else {
                // Legacy approach using TelephonyManager
                getPhoneNumbersLegacy(context, phoneNumbers)
            }
            
            Log.d(TAG, "soc-phone 📱 Phone numbers retrieved - SIM1: '${phoneNumbers["sim1"]}', SIM2: '${phoneNumbers["sim2"]}'")
            
        } catch (e: Exception) {
            Log.e(TAG, "soc-phone ❌ Failed to get phone numbers: ${e.message}")
        }
        
        return phoneNumbers
    }
    
    /**
     * 📱 Modern approach for Android 13+ using SubscriptionManager
     */
    @SuppressLint("MissingPermission")
    private fun getPhoneNumbersModern(context: Context, phoneNumbers: MutableMap<String, String>) {
        try {
            val subscriptionManager = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
            val activeSubscriptions = subscriptionManager.activeSubscriptionInfoList
            
            if (activeSubscriptions != null && !activeSubscriptions.isEmpty()) {
                Log.d(TAG, "soc-phone 📱 Found ${activeSubscriptions.size} active subscriptions")
                
                // Sort subscriptions by slot index to ensure consistent SIM1/SIM2 mapping
                val sortedSubscriptions = activeSubscriptions.sortedBy { it.simSlotIndex }
                
                for ((index, subscription) in sortedSubscriptions.withIndex()) {
                    if (index >= 2) break // Only support sim1 and sim2
                    
                    val simKey = "sim${index + 1}"
                    var phoneNumber: String? = null
                    
                    try {
                        // Method 1: Try SubscriptionManager.getPhoneNumber (most reliable on newer Android)
                        phoneNumber = subscriptionManager.getPhoneNumber(subscription.subscriptionId)
                        Log.d(TAG, "soc-phone 📱 Method 1 - $simKey from SubscriptionManager: $phoneNumber")
                        
                        // Method 2: If Method 1 fails, try to get from subscription directly
                        if (phoneNumber.isNullOrBlank() || phoneNumber == "unknown") {
                            phoneNumber = subscription.number
                            Log.d(TAG, "soc-phone 📱 Method 2 - $simKey from subscription.number: $phoneNumber")
                        }
                        
                        // Method 3: Try TelephonyManager for each subscription slot
                        if (phoneNumber.isNullOrBlank() || phoneNumber == "unknown") {
                            try {
                                val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                    // Try using subscription-specific telephony manager
                                    val subscriptionTelephony = telephonyManager.createForSubscriptionId(subscription.subscriptionId)
                                    phoneNumber = subscriptionTelephony.line1Number
                                    Log.d(TAG, "soc-phone 📱 Method 3 - $simKey from subscription telephony: $phoneNumber")
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "soc-phone ⚠️ Method 3 failed for $simKey: ${e.message}")
                            }
                        }
                        
                        // Method 4: Fallback to get line1Number from main telephony manager for first SIM
                        if ((phoneNumber.isNullOrBlank() || phoneNumber == "unknown") && index == 0) {
                            try {
                                val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
                                phoneNumber = telephonyManager.line1Number
                                Log.d(TAG, "soc-phone 📱 Method 4 - $simKey from main telephony: $phoneNumber")
                            } catch (e: Exception) {
                                Log.w(TAG, "soc-phone ⚠️ Method 4 failed for $simKey: ${e.message}")
                            }
                        }
                        
                        // Clean and validate the phone number
                        if (!phoneNumber.isNullOrBlank() && phoneNumber != "unknown") {
                            val cleanNumber = phoneNumber.trim().replace(Regex("[^+0-9]"), "")
                            if (cleanNumber.isNotEmpty() && cleanNumber.length >= 7) {
                                phoneNumbers[simKey] = cleanNumber
                                Log.d(TAG, "soc-phone ✅ $simKey: $cleanNumber")
                            } else {
                                Log.w(TAG, "soc-phone ⚠️ $simKey number too short or invalid: $cleanNumber")
                            }
                        } else {
                            Log.w(TAG, "soc-phone ⚠️ Could not retrieve valid phone number for $simKey")
                        }
                        
                    } catch (e: Exception) {
                        Log.e(TAG, "soc-phone ❌ Failed to get phone number for $simKey (subscription ${subscription.subscriptionId}): ${e.message}")
                    }
                }
            } else {
                Log.d(TAG, "soc-phone 📱 No active subscriptions found")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "soc-phone ❌ Modern phone number retrieval failed: ${e.message}")
            // Fallback to legacy method
            getPhoneNumbersLegacy(context, phoneNumbers)
        }
    }
    
    /**
     * 📱 Legacy approach for pre-Android 13 using TelephonyManager
     */
    @SuppressLint("MissingPermission")
    private fun getPhoneNumbersLegacy(context: Context, phoneNumbers: MutableMap<String, String>) {
        try {
            val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            
            // Get primary SIM phone number
            val line1Number = telephonyManager.line1Number
            if (!line1Number.isNullOrBlank() && line1Number != "unknown") {
                val cleanNumber = line1Number.trim().replace(Regex("[^+0-9]"), "")
                if (cleanNumber.isNotEmpty() && cleanNumber.length >= 7) {
                    phoneNumbers["sim1"] = cleanNumber
                    Log.d(TAG, "soc-phone 📱 SIM1 (legacy): $cleanNumber")
                } else {
                    Log.w(TAG, "soc-phone ⚠️ SIM1 number too short or invalid: $cleanNumber")
                }
            }
            
            // For dual SIM on older Android versions, try manufacturer-specific approaches
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    // Try to get IMEI for slot 1 and slot 2 to detect dual SIM
                    val imei1 = telephonyManager.getDeviceId(0)
                    val imei2 = telephonyManager.getDeviceId(1)
                    
                    Log.d(TAG, "soc-phone 📱 Legacy IMEI check - IMEI1: $imei1, IMEI2: $imei2")
                    
                    if (imei2 != null && imei2.isNotEmpty() && imei2 != "000000000000000") {
                        // Dual SIM device detected, try to get second SIM number
                        // Note: Most Android versions before 13 don't expose easy access to second SIM number
                        // This is a limitation of the Android API itself
                        Log.w(TAG, "soc-phone ⚠️ Dual SIM detected but second SIM number not accessible via legacy API")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "soc-phone ⚠️ Dual SIM detection failed in legacy mode: ${e.message}")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "soc-phone ❌ Legacy phone number retrieval failed: ${e.message}")
        }
    }
    
    /**
     * 📱 Check if phone permissions are granted
     */
    private fun hasPhonePermissions(context: Context): Boolean {
        val hasReadPhoneState = ActivityCompat.checkSelfPermission(
            context, 
            Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED
        
        val hasReadPhoneNumbers = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ActivityCompat.checkSelfPermission(
                context, 
                Manifest.permission.READ_PHONE_NUMBERS
            ) == PackageManager.PERMISSION_GRANTED
        } else true
        
        Log.d(TAG, "soc-phone 📱 Permission check - READ_PHONE_STATE: $hasReadPhoneState, READ_PHONE_NUMBERS: $hasReadPhoneNumbers")
        
        return hasReadPhoneState && hasReadPhoneNumbers
    }
    
    // Removed Device Owner permission granting logic
    
    /**
     * 📱 Get additional SIM information for debugging
     */
    fun getSimInfo(context: Context): Map<String, String> {
        val simInfo = mutableMapOf<String, String>()
        
        try {
            val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            
            simInfo["sim_serial"] = telephonyManager.simSerialNumber ?: ""
            simInfo["sim_operator"] = telephonyManager.simOperatorName ?: ""
            simInfo["network_operator"] = telephonyManager.networkOperatorName ?: ""
            simInfo["country_iso"] = telephonyManager.networkCountryIso ?: ""
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                simInfo["sim_carrier_id"] = telephonyManager.simCarrierId?.toString() ?: ""
            }
            
            Log.d(TAG, "soc-phone 📱 SIM Info: $simInfo")
            
        } catch (e: Exception) {
            Log.e(TAG, "soc-phone ❌ Failed to get SIM info: ${e.message}")
        }
        
        return simInfo
    }
    
    /**
     * 📱 Debug method to test phone number retrieval with detailed logging
     */
    fun debugPhoneNumbers(context: Context): Map<String, String> {
        Log.d(TAG, "soc-phone 📱 === DEBUG PHONE NUMBER RETRIEVAL ===")
        
        val phoneNumbers = getPhoneNumbers(context)
        
        Log.d(TAG, "soc-phone 📱 Final Result:")
        Log.d(TAG, "soc-phone 📱   SIM1: '${phoneNumbers["sim1"]}'")
        Log.d(TAG, "soc-phone 📱   SIM2: '${phoneNumbers["sim2"]}'")
        
        // Additional debug info
        try {
            val subscriptionManager = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
            val activeSubscriptions = subscriptionManager.activeSubscriptionInfoList
            
            if (activeSubscriptions != null) {
                Log.d(TAG, "soc-phone 📱 Active subscriptions: ${activeSubscriptions.size}")
                for ((index, sub) in activeSubscriptions.withIndex()) {
                    Log.d(TAG, "soc-phone 📱   Subscription $index: ID=${sub.subscriptionId}, Slot=${sub.simSlotIndex}, Carrier=${sub.carrierName}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "soc-phone ❌ Debug subscription info failed: ${e.message}")
        }
        
        Log.d(TAG, "soc-phone 📱 === END DEBUG ===")
        
        return phoneNumbers
    }
}




