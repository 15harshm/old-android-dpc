package com.renew.jss.storage

import android.content.Context

object LockedStateStore {

    private const val PREF = "emi_lock_state"
    private const val KEY = "locked"

    fun setLocked(context: Context, locked: Boolean) {
        context
            .getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY, locked)
            .apply()
    }

    fun isLocked(context: Context): Boolean {
        return context
            .getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getBoolean(KEY, false)
    }
}




