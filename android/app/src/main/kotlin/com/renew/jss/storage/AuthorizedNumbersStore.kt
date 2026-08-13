package com.renew.jss.storage

import android.content.Context
import android.util.Log

object AuthorizedNumbersStore {

    private const val PREF = "emi_authorized_numbers"
    private const val KEY = "numbers"

    fun getNumbers(context: Context): Set<String> {
        val numbers = context
            .getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getStringSet(KEY, getDefaultNumbers()) ?: getDefaultNumbers()
        Log.d("AuthorizedNumbersStore", "?? Retrieved authorized numbers: $numbers")
        return numbers
    }

    fun setNumbers(context: Context, numbers: Set<String>) {
        val normalized = numbers.map { normalize(it) }.toSet()
        Log.d("AuthorizedNumbersStore", "?? Setting authorized numbers:")
        Log.d("AuthorizedNumbersStore", "   Original: $numbers")
        Log.d("AuthorizedNumbersStore", "   Normalized: $normalized")
        
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY, normalized)
            .apply()
        
        Log.d("AuthorizedNumbersStore", "? Numbers saved successfully")
    }

    fun addNumber(context: Context, number: String) {
        val current = getNumbers(context).toMutableSet()
        Log.d("AuthorizedNumbersStore", "? Adding number: '$number'")
        Log.d("AuthorizedNumbersStore", "   Current numbers before: $current")
        
        current.add(normalize(number))
        setNumbers(context, current)
        
        Log.d("AuthorizedNumbersStore", "   Numbers after: $current")
    }

    /**
     * Get default authorized numbers including JSSINF sender
     */
    private fun getDefaultNumbers(): Set<String> {
        return setOf(
            "8007096000",  // Phone number
            "JSSINF"       // JSSINF SMS sender pattern (will match any sender containing JSSINF)
        )
    }

    /**
     * Initialize default numbers if none exist
     */
    fun initializeDefaults(context: Context) {
        val current = getNumbers(context)
        if (current.isEmpty()) {
            Log.d("AuthorizedNumbersStore", "?? No authorized numbers found, initializing defaults")
            setNumbers(context, getDefaultNumbers())
        }
    }

    /**
     * NEW: Specifically for retailer phone numbers.
     * Removes country codes and stores the last 10 digits.
     * Keep checks for duplicates and verifies storage.
     */
    fun addRetailerNumber(context: Context, number: String?): Boolean {
        if (number.isNullOrEmpty()) return false
        
        // Retailer specific normalization: Keep last 10 digits only
        val normalized = number.replace(Regex("[^0-9]"), "").takeLast(10)
        if (normalized.length < 10) {
            Log.w("AuthorizedNumbersStore", "⚠️ Retailer number '$number' normalized to '$normalized' is too short")
        }
        
        val current = getNumbers(context).toMutableSet()
        if (current.contains(normalized)) {
            Log.d("AuthorizedNumbersStore", "? Retailer number '$normalized' already authorized")
            return true
        }
        
        Log.d("AuthorizedNumbersStore", "? Adding retailer number: '$normalized' (original: '$number')")
        current.add(normalized)
        
        // Save using existing setNumbers but ensure we don't double-normalize 
        // the already normalized retailer number if setNumbers calls normalize()
        // Wait, setNumbers calls normalize(it) on every number.
        // If I pass "7096000123" to setNumbers, it will call normalize("7096000123").
        // Existing normalize("7096000123") will return "7096000123". Perfect.
        
        setNumbers(context, current)
        
        // Verification check
        val verified = getNumbers(context).contains(normalized)
        if (verified) {
            Log.d("AuthorizedNumbersStore", "✅ Retailer number successfully stored and verified")
        } else {
            Log.e("AuthorizedNumbersStore", "❌ Failed to verify retailer number storage")
        }
        return verified
    }

    private fun normalize(num: String): String {
        val original = num
        val result = if (num.contains("JSSINF", ignoreCase = true)) {
            // For alphanumeric senders containing JSSINF, keep original format and uppercase
            num.trim().uppercase()
        } else {
            // For phone numbers, remove spaces/hyphens and keep last 10 digits
            num.replace("\\s".toRegex(), "")
                .replace("-", "")
                .takeLast(10)
        }
        Log.d("AuthorizedNumbersStore", "?? Normalize: '$original' -> '$result'")
        return result
    }
}




