package com.renew.jss.policy

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log

/**
 * AppVisibilityPolicy - Remote App Icon Hide/Show
 *
 * Hides or shows the app launcher icon by toggling ONLY the launcher component
 * (`LauncherAlias`, the <activity-alias> that holds CATEGORY_LAUNCHER) via
 * PackageManager.setComponentEnabledSetting(). This needs NO Device Owner and no
 * special privilege at all — an app may always toggle its own components.
 *
 *   hide()  -> disable LauncherAlias                 -> icon removed from launcher
 *   show()  -> reset LauncherAlias to manifest default (enabled) -> icon restored
 *
 * MainActivity is intentionally NEVER touched: it carries no launcher intent-filter
 * (only LauncherAlias does), it is the alias's targetActivity, and it is the live
 * Flutter activity — disabling it does nothing for the icon and only risks confusing
 * OEM launchers / the running process. Services, FCM, and MainActivity stay fully
 * functional while the icon is hidden.
 *
 * DESIGN NOTE — why this is a single synchronous disable (not an enable-then-disable
 * "ping-pong"): the previous version enabled the components first and relied on an
 * 800ms delayed handler to disable them. If the process was killed or the service
 * restarted inside that window, the app was left ENABLED (i.e. NOT hidden). A single
 * disable has no such gap and matches the reference DPC that is known to work with
 * Device-Admin + Accessibility only.
 *
 * PERMISSIONS: DevicePolicyManager.setApplicationHidden() is Device Owner ONLY and is
 * deliberately NOT used here. Hiding needs only CHANGE_COMPONENT_ENABLED_STATE (which
 * is implicitly granted for an app's own components).
 */
object AppVisibilityPolicy {

    private const val TAG = "FCMPC_AppVisibilityPolicy"

    private const val LAUNCHER_ALIAS_CLASS = "com.renew.jss.LauncherAlias"

    private fun stateLabel(state: Int): String = when (state) {
        PackageManager.COMPONENT_ENABLED_STATE_DEFAULT             -> "DEFAULT(0)"
        PackageManager.COMPONENT_ENABLED_STATE_ENABLED             -> "ENABLED(1)"
        PackageManager.COMPONENT_ENABLED_STATE_DISABLED            -> "DISABLED(2)"
        PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER       -> "DISABLED_USER(3)"
        PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED -> "DISABLED_UNTIL_USED(4)"
        else                                                       -> "UNKNOWN($state)"
    }

    /** Applies [newState] to the launcher alias with DONT_KILL_APP. Never throws. */
    private fun setLauncherAliasState(context: Context, newState: Int) {
        try {
            val cn = ComponentName(context.packageName, LAUNCHER_ALIAS_CLASS)
            val before = context.packageManager.getComponentEnabledSetting(cn)
            Log.d(TAG, "AppVisibility: setting '${cn.flattenToShortString()}' " +
                "${stateLabel(before)} -> ${stateLabel(newState)}")
            context.packageManager.setComponentEnabledSetting(
                cn, newState, PackageManager.DONT_KILL_APP
            )
            val after = context.packageManager.getComponentEnabledSetting(cn)
            Log.d(TAG, "AppVisibility: '${cn.flattenToShortString()}' now ${stateLabel(after)}")
        } catch (e: Exception) {
            Log.e(TAG, "AppVisibility: setLauncherAliasState($newState) failed: ${e.message}", e)
        }
    }

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /** Hide the launcher icon by disabling the launcher alias (synchronous, one shot). */
    fun hide(context: Context) {
        Log.d(TAG, "AppVisibility: === HIDE called ===")
        setLauncherAliasState(context, PackageManager.COMPONENT_ENABLED_STATE_DISABLED)
    }

    /**
     * Show the launcher icon by resetting the launcher alias to its manifest default
     * (declared android:enabled="true", so DEFAULT resolves to enabled).
     */
    fun show(context: Context) {
        Log.d(TAG, "AppVisibility: === SHOW called ===")
        setLauncherAliasState(context, PackageManager.COMPONENT_ENABLED_STATE_DEFAULT)
    }

    /** @return true if the launcher alias is currently disabled (icon hidden). */
    fun isHidden(context: Context): Boolean {
        return try {
            val cn    = ComponentName(context.packageName, LAUNCHER_ALIAS_CLASS)
            val state = context.packageManager.getComponentEnabledSetting(cn)
            val hidden = state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            Log.d(TAG, "AppVisibility: isHidden=$hidden (state=${stateLabel(state)})")
            hidden
        } catch (e: Exception) {
            Log.e(TAG, "AppVisibility: isHidden check failed: ${e.message}", e)
            false
        }
    }
}
