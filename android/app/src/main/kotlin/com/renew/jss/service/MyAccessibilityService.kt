package com.renew.jss.service

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.google.firebase.messaging.FirebaseMessaging
import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings

/**
 * ðŸ›¡ï¸ MINIMAL ACCESSIBILITY SERVICE - NO LAG
 */
class MyAccessibilityService : AccessibilityService() {
    
    companion object {
        private const val TAG_SERVICE = "MyAccessibilityService"
        @Volatile
        var instance: MyAccessibilityService? = null

        fun disableService(context: Context) {
            instance?.let { serviceInstance ->
                Handler(Looper.getMainLooper()).post {
                    try {
                        serviceInstance.disableSelf()
                        Log.d(TAG_SERVICE, "disableSelf() called successfully")
                    } catch (e: Exception) {
                        Log.e(TAG_SERVICE, "Failed to disable accessibility service: ${e.message}")
                    }
                }
            } ?: Log.w(TAG_SERVICE, "Accessibility service instance is null, cannot disableSelf")
        }

        /**
         * ♿ Programmatically ENABLE our accessibility service via Settings.Secure.
         * Requires WRITE_SECURE_SETTINGS (declared in the manifest, granted at
         * provisioning via `adb pm grant` — the same grant that lets LocationPolicy
         * write LOCATION_MODE). Unlike disableService(), this does NOT need a running
         * instance — it flips the secure setting, and the system then binds the
         * service. Returns true if the enable was written, false if the permission
         * isn't granted (caller can then fall back to prompting the user).
         */
        fun enableService(context: Context): Boolean {
            return try {
                val component =
                    ComponentName(context, MyAccessibilityService::class.java).flattenToString()
                val resolver = context.contentResolver
                val current = Settings.Secure.getString(
                    resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                ) ?: ""

                val alreadyEnabled = current.split(':').any {
                    it.equals(component, ignoreCase = true) ||
                        (it.isNotEmpty() && it.startsWith(context.packageName))
                }
                if (!alreadyEnabled) {
                    val updated = if (current.isEmpty()) component else "$current:$component"
                    Settings.Secure.putString(
                        resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, updated
                    )
                    Log.d(TAG_SERVICE, "♿ enableService: added $component to enabled services")
                } else {
                    Log.d(TAG_SERVICE, "♿ enableService: service already in enabled list")
                }
                // Master accessibility switch ON so the system actually binds services.
                Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
                Log.d(TAG_SERVICE, "✅ enableService: accessibility enabled via Settings.Secure")
                true
            } catch (e: SecurityException) {
                // WRITE_SECURE_SETTINGS not granted on this device — cannot self-enable.
                Log.e(TAG_SERVICE, "❌ enableService: WRITE_SECURE_SETTINGS not granted: ${e.message}")
                false
            } catch (e: Exception) {
                Log.e(TAG_SERVICE, "❌ enableService failed: ${e.message}")
                false
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.d("Watchdog", "Accessibility service instance set in onCreate")
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) {
            instance = null
        }
        Log.d("Watchdog", "Accessibility service instance cleared in onDestroy")
    }
    
    private var launcherPackageName: String? = null

    // âœ… ANR FIX: Initialize to (now - 25s) so the first watchdog fires ~5s after
    // service connects, not instantly on the very first accessibility event at launch.
    private var lastWatchdogRun = System.currentTimeMillis() - 25000L
    private var lastKioskLaunchTime = 0L
    // âœ… ANR FIX: Prevent multiple concurrent postDelayed blocks queuing on the main thread.
    // Without this, rapid accessibility events (taps, window changes) each post a new
    // startForegroundService batch â†’ floods the main thread message queue â†’ ANR.
    @Volatile private var watchdogPending = false

    // 🛡️ System package-installer packages (OEM variants). Their "Uninstall this
    // app?" / "Deactivate & uninstall?" confirm dialog is a SEPARATE package from
    // Settings — see the uninstall guard in onAccessibilityEvent.
    private val packageInstallerPackages = setOf(
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.miui.packageinstaller",
        "com.samsung.android.packageinstaller",
        "com.oplus.packageinstaller",
        "com.vivo.packageinstaller"
    )

    // Timestamp of the last time OUR app's App Info / permission screen was seen.
    // Lets the uninstall guard catch the confirm dialog that opens right after it,
    // even when that system dialog doesn't spell out our app name.
    @Volatile private var lastOurAppInfoSeen = 0L

    private fun isSetupCompleted(): Boolean {
        return try {
            val prefs = getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)
            prefs.getBoolean("flutter.permissions_setup_completed", false)
        } catch (e: Exception) {
            false
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        launcherPackageName = getLauncherPackageName()
        
        if (!isSetupCompleted()) {
            return
        }
        
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        handler.postDelayed({
            try {
                val prefs = getSharedPreferences("device_admin_setup", Context.MODE_PRIVATE)
                val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
                val admin = android.content.ComponentName(this, com.renew.jss.DeviceOwnerReceiver::class.java)
                
                if (dpm.isAdminActive(admin)) {
                    prefs.edit().putBoolean("setup_completed", true).apply()
                    lastWatchdogRun = 0L 
                    ensureServicesAlive()
                }
            } catch (e: Exception) {
                Log.e("Watchdog", "Error in delayed start: ${e.message}")
            }
        }, 1500)
    }

    private fun getLauncherPackageName(): String {
        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN)
        intent.addCategory(android.content.Intent.CATEGORY_HOME)
        val resolveInfo = packageManager.resolveActivity(intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
        return resolveInfo?.activityInfo?.packageName ?: "com.google.android.apps.nexuslauncher"
    }
    
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        try {
            if (!isSetupCompleted()) {
                return
            }
            
            // ðŸ›¡ï¸ WATCHDOG: Throttled watchdog â€” ensureServicesAlive has its own 30s rate limit
            ensureServicesAlive()
            
            if (event == null) return
            
            val packageName = event.packageName?.toString() ?: return
            val className = event.className?.toString() ?: ""
            val eventTextList = event.text ?: emptyList<CharSequence>()
            val text = eventTextList.joinToString(" ")
            val contentDescription = event.contentDescription?.toString() ?: ""

            // 🛡️ UNINSTALL GUARD — the system package installer is a SEPARATE
            // package from Settings; its "Uninstall this app?" / "Deactivate &
            // uninstall?" confirm dialog used to be unmonitored, leaving the race
            // where a user could confirm before we reacted. We act ONLY when the
            // dialog concerns OUR app — matched by our (per-flavor) app label, by
            // our package com.renew.jss, or because it opened moments after our own
            // App Info screen. Uninstalling ANY OTHER app is untouched, so there is
            // no abnormality during normal phone use.
            if (packageName in packageInstallerPackages) {
                val appLabel = applicationContext.applicationInfo.loadLabel(packageManager).toString()
                val sourceNode = event.source
                val refersToOurApp = text.contains(appLabel, ignoreCase = true) ||
                    contentDescription.contains(appLabel, ignoreCase = true) ||
                    nodeContainsText(sourceNode, appLabel) ||
                    nodeContainsText(sourceNode, "com.renew.jss")
                val rightAfterOurAppInfo =
                    System.currentTimeMillis() - lastOurAppInfoSeen < 4000L
                if (refersToOurApp || rightAfterOurAppInfo) {
                    Log.w("Accessibility", "🚫 Blocked uninstall/deactivate dialog for our app [$packageName]")
                    performGlobalAction(GLOBAL_ACTION_BACK)
                    performGlobalAction(GLOBAL_ACTION_HOME)
                    return
                }
            }

            // 1. Play Store checks (only block if user is trying to uninstall or manage apps)
            if (packageName == "com.android.vending") {
                if (text.contains("Manage apps", ignoreCase = true) || text.contains("My apps", ignoreCase = true)) {
                    Log.d("Accessibility", "Play store manage apps & device opened!")
                    blockPlayStore()
                    return
                }
            }

            // 2. Play Protect check (when package is NOT Play Store itself but text contains Play Protect)
            if (packageName != "com.android.vending" && text.contains("Play Protect", ignoreCase = true)) {
                Log.d("Accessibility", "Play protect opened!")
                blockPlayStore()
                return
            }


            // 4. Target Settings/Security package switch
            val settingsPackages = setOf(
                "com.android.settings",                  // Standard Settings
                "com.samsung.android.settings",          // Samsung Settings
                "com.samsung.accessibility",             // Samsung dedicated Accessibility package
                "com.samsung.android.accessibility",     // Samsung Accessibility (variant)
                "com.google.android.settings.intelligence", // Settings Intelligence
                "com.google.android.permissioncontroller", // Permission Controller
                "com.miui.securitycenter",               // Xiaomi Security Center
                "com.miui.appmanager",                   // Xiaomi/POCO/Redmi App manager (App info screen)
                "com.coloros.safecenter",                // Oppo Phone Manager
                "com.oppo.safe",                         // Older Oppo
                "com.oplus.notificationmanager",         // Oppo Notification Manager
                "com.oplus.battery",                     // Oppo Battery
                "com.iqoo.secure",                       // Vivo iManager
                "com.vivo.permissionmanager",            // Vivo Permissions
                "com.transsion.phonemanager",            // Tecno/Infinix Phone Manager
                "com.lenovo.security"                    // Lenovo Security
            )

            // 3. General Security check — ONLY for known settings packages to avoid false positives.
            // DO NOT broaden this to all packages: banking/UPI/social apps show "Security" text
            // in their own UI and would get falsely blocked.
            // ALLOW password & screen lock settings through — users must be able to change these.
            val isPasswordOrLockScreen = text.contains("password", ignoreCase = true) ||
                text.contains("screen lock", ignoreCase = true) ||
                text.contains("lock screen", ignoreCase = true) ||
                text.contains("unlock", ignoreCase = true) ||
                text.contains("PIN", ignoreCase = false) ||
                text.contains("pattern", ignoreCase = true) ||
                text.contains("fingerprint", ignoreCase = true) ||
                text.contains("biometric", ignoreCase = true) ||
                text.contains("face unlock", ignoreCase = true) ||
                text.contains("change password", ignoreCase = true) ||
                text.contains("set password", ignoreCase = true) ||
                className.contains("ScreenLock", ignoreCase = true) ||
                className.contains("ChooseLock", ignoreCase = true) ||
                className.contains("SetupLock", ignoreCase = true) ||
                className.contains("ConfirmLock", ignoreCase = true) ||
                className.contains("Biometric", ignoreCase = true) ||
                className.contains("Fingerprint", ignoreCase = true)

            if (packageName in settingsPackages && packageName != "com.android.systemui" && text.contains("Security", ignoreCase = true) && !isPasswordOrLockScreen) {
                Log.d("Accessibility", "Security settings screen opened! [$packageName]")
                blockSecuritySettings()
                return
            }

            if (packageName in settingsPackages) {

                // 🛡️ ACCESSIBILITY SETTINGS GUARD — the ENTIRE DPC depends on this
                // service staying enabled. Once the user disables it we receive no
                // more events and cannot react (a Device Admin, unlike a Device
                // Owner, cannot re-enable its own accessibility service). So we must
                // block the Accessibility screens BEFORE the on/off toggle is
                // reachable. Detect by the settings screen's own class/title — which
                // is reliable at the FIRST window event — and eject hard (BACK+HOME),
                // not to the settings home where the user can just walk back in.
                // The Accessibility LIST is the choke point: block it and the
                // per-service toggle is unreachable.
                // Detection is LANGUAGE-INDEPENDENT: package + class names stay in
                // English even when the phone UI is localized (Bengali/Hindi/…),
                // unlike the on-screen title. This is why the old title/name check
                // failed on real devices. Stock + most OEMs host Accessibility in
                // com.android.settings.accessibility.* activity classes; Samsung uses
                // its own com.samsung.accessibility package. The English-title check
                // is kept only as an extra net for stock-English devices.
                val isAccessibilityScreen =
                    packageName.contains("accessibility", ignoreCase = true) ||
                    className.contains("accessibility", ignoreCase = true) ||
                    (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
                        text.contains("Accessibility", ignoreCase = true))
                if (isAccessibilityScreen) {
                    Log.w("Accessibility", "🚫 Blocked Accessibility settings screen [$packageName / $className]")
                    performGlobalAction(GLOBAL_ACTION_BACK)
                    performGlobalAction(GLOBAL_ACTION_HOME)
                    return
                }

                // Developer options
                if (text.contains("Developer", ignoreCase = true) ||
                    contentDescription.contains("Developer", ignoreCase = true) ||
                    className.contains("DevelopmentSettings", ignoreCase = true) ||
                    className.contains("DeveloperSettingsActivity", ignoreCase = true)) {
                    Log.d("Accessibility", "Developer Options opened!")
                    blockAction()
                    return
                }

                // App Notification settings
                if (text.contains("app notification", ignoreCase = true)) {
                    Log.d("Accessibility", "App Notification settings opened!")
                    blockAction()
                    return
                }

                // Device Admin Apps screen
                if (text.contains("device admin", ignoreCase = true) ||
                    contentDescription.contains("device admin", ignoreCase = true)) {
                    Log.d("Accessibility", "Device Admin Apps screen opened!")
                    blockActionBack()
                    return
                }

                // Security settings (but allow password/lock screen changes through)
                if (text.contains("Security", ignoreCase = true) && !isPasswordOrLockScreen) {
                    blockSecuritySettings()
                    return
                }

                // Our app name on any Settings screen — the LAST line of defence.
                // The per-service accessibility TOGGLE screen shows our app name as
                // its title (locale-independent, since our brand name isn't
                // translated), so this catches the on/off switch page even when the
                // package/class guard above missed it (e.g. SubSettings-hosted on a
                // localized device). Also covers App Info / per-app notification /
                // battery pages — all routes that lead to disabling or uninstalling
                // us. Eject HARD (not to Settings home, where the user just walks
                // back), and arm the uninstall guard in case this is our App Info.
                val friendlyAppName = getString(com.renew.jss.R.string.app_name)
                val isWindowState = event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                if (text.contains(friendlyAppName, ignoreCase = true) ||
                    contentDescription.contains(friendlyAppName, ignoreCase = true) ||
                    (isWindowState && nodeContainsText(event.source, friendlyAppName))) {
                    Log.w("Accessibility", "🚫 Settings screen referencing our app — ejecting [$className]")
                    lastOurAppInfoSeen = System.currentTimeMillis()
                    performGlobalAction(GLOBAL_ACTION_BACK)
                    performGlobalAction(GLOBAL_ACTION_HOME)
                    return
                }

                // App Info screen for OUR APP. Cover OEM variants by class name
                // (class names stay in English on every locale, unlike the title):
                //  - stock/most OEMs: AppInfoDashboard*, InstalledAppDetails*
                //  - MIUI / POCO / Redmi: com.miui.appmanager.ApplicationsDetailsActivity
                //  - misc OEM app-detail activities: *AppDetail*
                val isAppInfoClass = className.contains("AppInfo", ignoreCase = true) ||
                                     className.contains("InstalledAppDetails", ignoreCase = true) ||
                                     className.contains("ApplicationsDetails", ignoreCase = true) ||  // MIUI/POCO
                                     className.contains("appmanager", ignoreCase = true) ||           // MIUI app manager
                                     className.contains("AppManager", ignoreCase = true) ||
                                     className.contains("AppDetail", ignoreCase = true) ||            // generic OEM
                                     text.contains("app info", ignoreCase = true) ||
                                     text.contains("permission", ignoreCase = true)
                if (isAppInfoClass) {
                    val appLabel = applicationContext.applicationInfo.loadLabel(packageManager).toString()
                    val sourceNode = event.source
                    val isOurApp = nodeContainsText(sourceNode, appLabel) || 
                                   text.contains(appLabel, ignoreCase = true) || 
                                   text.contains("com.renew.jss", ignoreCase = true)
                    if (isOurApp) {
                        Log.d("Accessibility", "âœ… User opened App Info screen of OUR APP!")
                        // Arm the uninstall guard: if a package-installer confirm
                        // dialog appears in the next few seconds, it's ours -> block.
                        lastOurAppInfoSeen = System.currentTimeMillis()
                        blockActionBack()
                        return
                    }
                    if (nodeContainsText(sourceNode, "Google Play Services") || text.contains("Google Play Services", ignoreCase = true)) {
                        Log.d("Accessibility", "âœ… User opened App Info screen of Google Play Services!")
                        blockAction()
                        return
                    }
                    if (nodeContainsText(sourceNode, "Phone Manager") || text.contains("Phone Manager", ignoreCase = true)) {
                        Log.d("Accessibility", "âœ… User opened App Info screen of Phone Manager!")
                        blockAction()
                        return
                    }
                    if (nodeContainsText(sourceNode, "Payment Protection") || text.contains("Payment Protection", ignoreCase = true)) {
                        Log.d("Accessibility", "âœ… User opened App Info screen of Payment Protection!")
                        blockAction()
                        return
                    }
                    if (nodeContainsText(sourceNode, "Smart Sidebar") || text.contains("Smart Sidebar", ignoreCase = true)) {
                        Log.d("Accessibility", "âœ… User opened App Info screen of Smart Sidebar!")
                        blockAction()
                        return
                    }
                }

                // Background autostart / Sim toolkit
                if (text.contains("Background autostart", ignoreCase = true) ||
                    text.contains("sim toolkit", ignoreCase = true)) {
                    Log.d("Accessibility", "ðŸš¨ User opened permissions screen!")
                    blockAction()
                    return
                }

                // Factory Reset detection
                val isFactoryResetClass = className.contains("MasterClear", ignoreCase = true) ||
                                         className.contains("Reset", ignoreCase = true) ||
                                         className.contains("Factory", ignoreCase = true)
                val isFactoryResetText = text.contains("Factory", ignoreCase = true) ||
                                        text.contains("Reset", ignoreCase = true) ||
                                        text.contains("Erase all", ignoreCase = true) ||
                                        text.contains("Factory data reset", ignoreCase = true) ||
                                        text.contains("Delete all", ignoreCase = true)
                if (isFactoryResetClass || isFactoryResetText) {
                    Log.d("Accessibility", "ðŸš¨ User opened Factory Reset screen!")
                    blockAction()
                    return
                }

                // Uninstall screen for OUR APP
                val sourceNode = event.source
                val hasUninstallText = nodeContainsText(sourceNode, "uninstall") || text.contains("uninstall", ignoreCase = true)
                if (hasUninstallText) {
                    val appLabel = applicationContext.applicationInfo.loadLabel(packageManager).toString()
                    if (nodeContainsText(sourceNode, appLabel) || text.contains(appLabel, ignoreCase = true)) {
                        Log.d("Accessibility", "Uninstall Screen Detected for OUR APP")
                        blockActionBack()
                        return
                    }
                }
            }

            // ðŸ–¼ï¸ BLOCK WALLPAPER CHANGES
            if (com.renew.jss.policy.WallpaperPolicy.isLocked(this)) {
                val lowerClassName = className.lowercase()
                val lowerText = text.lowercase()
                
                // Block Wallpaper, Personalization, and Display settings if they are used to change wallpaper
                if (lowerClassName.contains("wallpaper") || 
                    lowerClassName.contains("personalization") || 
                    lowerClassName.contains("theme") ||
                    lowerText.contains("wallpaper") || 
                    lowerText.contains("personalization") || 
                    lowerText.contains("set as wallpaper")) {
                    
                    Log.w("Accessibility", "ðŸš« Blocked wallpaper change attempt")
                    performGlobalAction(GLOBAL_ACTION_HOME)
                    return
                }
            }
            
            // ðŸ›¡ï¸ BLOCK RECENT APPS / SYSTEM UI / MULTITASKING MENUS
            // ðŸš¨ CRITICAL: Do NOT block our own app (com.renew.jss)
            val isOurApp = packageName == "com.renew.jss"
            val isSystemOrLauncher = packageName == "com.android.systemui" || packageName == launcherPackageName
            
            if (!isOurApp && isSystemOrLauncher && com.renew.jss.storage.LockedStateStore.isLocked(this)) {
                val lowerClassName = className.lowercase()
                val lowerText = text.lowercase()
                
                // Block Recents, Split-Screen, App Pinning/Locking, and Multitasking menus
                if (lowerClassName.contains("recent") || 
                    lowerClassName.contains("task") || 
                    lowerClassName.contains("overview") ||
                    lowerClassName.contains("split") ||
                    lowerClassName.contains("multiwindow") ||
                    lowerClassName.contains("floating") ||
                    lowerText.contains("split") ||
                    lowerText.contains("unlock") || // Matches "Unlock" in recents menu
                    lowerText.contains("app info") ||
                    lowerText.contains("pin") ||
                    lowerText.contains("lock")) {
                    
                    Log.w("Accessibility", "ðŸš« Blocked multitasking/split-screen interaction: $packageName - $className ($text)")
                    performGlobalAction(GLOBAL_ACTION_HOME)
                    return
                }
            }

            // ðŸŽ¯ Launcher detection for Kiosk Mode stability
            if (packageName == launcherPackageName || className.contains("Launcher")) {
                if (com.renew.jss.storage.LockedStateStore.isLocked(this)) {
                    val now = System.currentTimeMillis()
                    if (now - lastKioskLaunchTime > 1000L) {
                        lastKioskLaunchTime = now
                        val kioskIntent = android.content.Intent(this, com.renew.jss.activity.KioskActivity::class.java)
                        kioskIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                                           android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP or
                                           android.content.Intent.FLAG_ACTIVITY_NO_ANIMATION)
                        startActivity(kioskIntent)
                    }
                }
            }
        } catch (e: Exception) {
            // Silent fail
        }
    }

    private fun nodeContainsText(node: AccessibilityNodeInfo?, searchText: String): Boolean {
        if (node == null) return false
        try {
            val text = node.text?.toString()
            val desc = node.contentDescription?.toString()
            if ((text != null && text.contains(searchText, ignoreCase = true)) ||
                (desc != null && desc.contains(searchText, ignoreCase = true))) {
                return true
            }
            val childCount = node.childCount
            for (i in 0 until childCount) {
                val child = node.getChild(i) ?: continue
                if (nodeContainsText(child, searchText)) {
                    return true
                }
            }
        } catch (e: Exception) {
            // ignore
        }
        return false
    }

    private fun blockAction() {
        try {
            val intent = Intent("android.settings.SETTINGS")
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            startActivity(intent)
        } catch (e: Exception) {
            blockActionBack()
        }
    }

    private fun blockPlayStore() {
        try {
            val intent = packageManager.getLaunchIntentForPackage("com.android.vending")
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                startActivity(intent)
            } else {
                blockActionBack()
            }
        } catch (e: Exception) {
            blockActionBack()
        }
    }

    private fun blockActionBack() {
        performGlobalAction(GLOBAL_ACTION_BACK)
        performGlobalAction(GLOBAL_ACTION_BACK)
        performGlobalAction(GLOBAL_ACTION_BACK)
        performGlobalAction(GLOBAL_ACTION_BACK)
    }

    private fun blockSecuritySettings() {
        Log.d("Accessibility", "Security settings screen opened!")
        try {
            blockAction()
            val handler = Handler(Looper.getMainLooper())
            handler.postDelayed({
                try {
                    val intent = Intent(this, com.renew.jss.activity.MainActivity::class.java)
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                } catch (e: Exception) {
                    blockAction()
                }
            }, 500L)
        } catch (e: Exception) {
            blockAction()
        }
    }

    /**
     * ðŸ›¡ï¸ WATCHDOG: Restarts core services if they were killed
     */
    private fun ensureServicesAlive() {
        if (!isSetupCompleted()) {
            return
        }
        val now = System.currentTimeMillis()
        // Opt #1: Throttle to run at most once every 60 seconds.
        // ScreenActionReceiver handles immediate revival on screen unlock,
        // so 60s here reduces redundant service starts without losing coverage.
        if (now - lastWatchdogRun < 60000) return
        lastWatchdogRun = now

        // âœ… ANR FIX: If a watchdog postDelayed is already queued, skip scheduling another.
        // This prevents the main thread from being flooded during rapid accessibility events.
        if (watchdogPending) {
            return
        }
        watchdogPending = true

        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        handler.postDelayed({
            watchdogPending = false
            try {
                // 1. Restart AlwaysAliveService (Core heartbeat)
                val aliveIntent = android.content.Intent(this, AlwaysAliveService::class.java)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    startForegroundService(aliveIntent)
                } else {
                    startService(aliveIntent)
                }

                // 2. Restart PolicyMonitoringService only if not already running
                if (!PolicyMonitoringService.isRunning) {
                    val policyIntent = android.content.Intent(this, PolicyMonitoringService::class.java)
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        startForegroundService(policyIntent)
                    } else {
                        startService(policyIntent)
                    }
                }

                // 3. KioskEnforcementService only if locked
                if (com.renew.jss.storage.LockedStateStore.isLocked(this)) {
                    val kioskIntent = android.content.Intent(this, KioskEnforcementService::class.java)
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        startForegroundService(kioskIntent)
                    } else {
                        startService(kioskIntent)
                    }
                }

                // 4. FCM token refresh
                FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                    if (task.isSuccessful) {
                    }
                }
            } catch (e: Exception) {
                Log.e("Watchdog", "ensureServicesAlive error: ${e.message}")
            }
        }, 500L)
    }


    override fun onInterrupt() {
        // Empty
    }
}
