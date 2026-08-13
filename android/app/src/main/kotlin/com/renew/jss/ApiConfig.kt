package com.renew.jss

/**
 * Centralized API Configuration for EMI Locker Application.
 *
 * DOMAIN / APP_NAME / API_BASE / SOCKET_BASE are injected per flavor by Gradle
 * (BuildConfig) from the single source of truth:
 *   tool/flavors/flavors.yaml  ->  android/flavors.gen.json  ->  build.gradle.kts
 * Do NOT reintroduce per-flavor when(BuildConfig.FLAVOR) tables here.
 */
object ApiConfig {

    val DOMAIN = BuildConfig.DOMAIN
    val APP_NAME = BuildConfig.APP_NAME

    const val GENERIC_APP_NAME = "Secure Device"

    private val API_BASE = BuildConfig.API_BASE

    // Flattened API Endpoints
    val GET_FRP = "$API_BASE/getFrp.php"
    val UPDATE_COMMAND_STATUS = "$API_BASE/update_command_status.php"
    val SET_DEVICE_LOCATION = "$API_BASE/setDeviceLocation.php"
    val DEVICE_STATUS_UPDATE = "$API_BASE/DeviceStatusUpdate.php"
    // Heartbeat/ping: backend sends a silent NOTIFICATION FCM, app pings this to report online.
    val DEVICE_ACTIVE_STATUS = "$API_BASE/FCM_DeviceActiveStatus.php"
    val WALLPAPER_URL = "$API_BASE/wallpaper/wallpaper.png"
    val LOCKED_DEVICE_DESC = "$API_BASE/LockedDeviceDesc.php"
    val SAVE_FCM_TOKEN = "${BuildConfig.SOCKET_BASE}/save-fcm-token"

    val FCM_TOKEN_ENDPOINT = "${BuildConfig.SOCKET_BASE}/save-fcm-token"

    fun getBaseDomain(): String = API_BASE
    // API_BASE already ends in "/api2"; returning it directly (previously this
    // appended a second "/api2", producing a double ".../api2/api2" URL).
    fun getApiBaseUrl(): String = API_BASE

    // Compatibility layer for nested Api object if needed by other files
    object Api {
        val GET_FRP get() = ApiConfig.GET_FRP
        val UPDATE_COMMAND_STATUS get() = ApiConfig.UPDATE_COMMAND_STATUS
        val SET_DEVICE_LOCATION get() = ApiConfig.SET_DEVICE_LOCATION
        val DEVICE_STATUS_UPDATE get() = ApiConfig.DEVICE_STATUS_UPDATE
        val DEVICE_ACTIVE_STATUS get() = ApiConfig.DEVICE_ACTIVE_STATUS
        val WALLPAPER_URL get() = ApiConfig.WALLPAPER_URL
        val LOCKED_DEVICE_DESC get() = ApiConfig.LOCKED_DEVICE_DESC
        val SAVE_FCM_TOKEN get() = ApiConfig.SAVE_FCM_TOKEN
    }
}
