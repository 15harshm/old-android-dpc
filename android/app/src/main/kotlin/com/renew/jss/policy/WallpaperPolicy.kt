package com.renew.jss.policy

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.DisplayMetrics
import android.util.Log
import com.renew.jss.ApiConfig
import com.renew.jss.BuildConfig
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

object WallpaperPolicy {
    
    private const val TAG = "WallpaperPolicy"
    private const val WALLPAPER_FILE_NAME = "emi_wallpaper.png"
    // The customer's ORIGINAL wallpaper, saved (in persistent filesDir, not cache)
    // just before we overwrite it with the branded one, so unset() can restore it
    // instead of clearing to the system default.
    private const val ORIGINAL_WALLPAPER_FILE = "original_wallpaper.png"
    private const val PREF_WALLPAPER_LOCKED = "wallpaper_locked"
    private const val PREFS_NAME = "wallpaper_prefs"

    // 🎯 CLIENT-SPECIFIC: the "never reset the customer's own wallpaper + save/restore
    // the original" behavior is enabled ONLY for this flavor. Every other client keeps
    // the original behavior (unset() clears to the system default). To roll it out to
    // more clients later, add their flavor id here.
    private val WALLPAPER_PRESERVE_FLAVORS = setOf("nexorha")
    private fun preservesWallpaper(): Boolean = BuildConfig.FLAVOR in WALLPAPER_PRESERVE_FLAVORS

    /**
     * Check if the wallpaper is currently locked by DPC
     */
    fun isLocked(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(PREF_WALLPAPER_LOCKED, false)
    }

    private fun setLockedState(context: Context, locked: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(PREF_WALLPAPER_LOCKED, locked).apply()
        
        // 🛡️ Try to apply system-level restriction
        // (Removed as it requires Device Owner)
    }
    
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
    
    /**
     * Set wallpaper from the provided URL
     */
    fun set(context: Context) {
        Log.d(TAG, "🖼️ Setting wallpaper from URL: ${ApiConfig.Api.WALLPAPER_URL}")
        
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Download wallpaper
                val wallpaperFile = downloadWallpaper(context)
                if (wallpaperFile != null) {
                    // 💾 (preserve-clients only) Save the customer's CURRENT wallpaper
                    // before we overwrite it, so unset() can put it back later.
                    if (preservesWallpaper()) {
                        saveOriginalWallpaper(context)
                    }

                    // Apply wallpaper
                    applyWallpaper(context, wallpaperFile)

                    // 🔒 Lock wallpaper changes
                    setLockedState(context, true)
                    
                    Log.d(TAG, "✅ Wallpaper set successfully")
                } else {
                    Log.e(TAG, "❌ Failed to download wallpaper")
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error setting wallpaper: ${e.message}", e)
            }
        }
    }
    
    /**
     * Remove/unset wallpaper
     */
    fun unset(context: Context) {
        Log.d(TAG, "🗑️ unset() requested")

        val preserve = preservesWallpaper()

        // 🛡️ PRESERVE-CLIENTS ONLY (e.g. nexorha): only ever revert a wallpaper WE
        // actually set. If the DPC never applied a branded wallpaper (isLocked ==
        // false) we do NOTHING — clearing here would wipe the customer's own
        // wallpaper to the system default. This is what happens when the server
        // bundles set_wallpaper:false in a full policy payload on a normal
        // lock/unlock. For all OTHER clients, behavior is unchanged (clear below).
        if (preserve && !isLocked(context)) {
            Log.d(TAG, "🖼️ [${BuildConfig.FLAVOR}] unset ignored — no DPC wallpaper set; customer wallpaper left intact")
            return
        }

        // Bitmap decode + setBitmap can be heavy; run off the caller's thread
        // (unset can be invoked from the policy-dispatch thread) to avoid ANRs.
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // ♻️ Preserve-clients: try to restore the customer's saved original
                // first. Everyone else (and the fallback when nothing was saved)
                // clears to the system default — the original behavior.
                val restored = preserve && restoreOriginalWallpaper(context)
                if (!restored) {
                    val wallpaperManager = WallpaperManager.getInstance(context)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        wallpaperManager.clearWallpaper()
                    } else {
                        @Suppress("DEPRECATION")
                        wallpaperManager.clear()
                    }
                    Log.d(TAG, "🗑️ Cleared wallpaper to system default")
                }

                // Clean up downloaded wallpaper file
                deleteWallpaperFile(context)

                // 🔓 Unlock wallpaper changes
                setLockedState(context, false)

                Log.d(TAG, "✅ Wallpaper unset completed")

            } catch (e: Exception) {
                Log.e(TAG, "❌ Error removing wallpaper: ${e.message}", e)
            }
        }
    }

    /**
     * 💾 Save the customer's current wallpaper to persistent storage BEFORE we
     * overwrite it with the branded one. Best-effort: reading the current
     * wallpaper is restricted on newer Android, so if we can't read it we simply
     * don't save (unset() then falls back to clearing to default).
     */
    private fun saveOriginalWallpaper(context: Context) {
        // Don't overwrite a previously-saved original if set() runs again while a
        // branded wallpaper is already applied.
        if (isLocked(context)) {
            Log.d(TAG, "💾 Already locked — keeping previously saved original")
            return
        }
        val originalFile = File(context.filesDir, ORIGINAL_WALLPAPER_FILE)
        if (originalFile.exists()) {
            Log.d(TAG, "💾 Original wallpaper already saved — skipping")
            return
        }
        try {
            val wallpaperManager = WallpaperManager.getInstance(context)
            var bitmap: Bitmap? = null
            var source = "none"

            // 1) Static wallpaper file — the most reliable, no permission needed.
            //    Try the home (SYSTEM) wallpaper, then the lock-screen one.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                for (flag in intArrayOf(WallpaperManager.FLAG_SYSTEM, WallpaperManager.FLAG_LOCK)) {
                    if (bitmap != null) break
                    try {
                        val pfd: ParcelFileDescriptor? = wallpaperManager.getWallpaperFile(flag)
                        if (pfd != null) {
                            pfd.use { bitmap = BitmapFactory.decodeFileDescriptor(it.fileDescriptor) }
                            if (bitmap != null) source = "getWallpaperFile(flag=$flag)"
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "💾 getWallpaperFile(flag=$flag) failed: ${e.message}")
                    }
                }
            }

            // 2) Fallback: current wallpaper drawable → bitmap. Handles ANY drawable
            //    type (not just BitmapDrawable) by rasterising it onto a canvas.
            if (bitmap == null) {
                try {
                    val d: Drawable? = wallpaperManager.drawable // getDrawable()
                    if (d != null) {
                        bitmap = drawableToBitmap(context, d)
                        if (bitmap != null) source = "getDrawable()"
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "💾 getDrawable failed: ${e.message}")
                }
            }

            // 3) Last resort: peekDrawable (may be null if none cached).
            if (bitmap == null) {
                try {
                    val d: Drawable? = wallpaperManager.peekDrawable()
                    if (d != null) {
                        bitmap = drawableToBitmap(context, d)
                        if (bitmap != null) source = "peekDrawable()"
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "💾 peekDrawable failed: ${e.message}")
                }
            }

            if (bitmap != null) {
                FileOutputStream(originalFile).use { out ->
                    bitmap!!.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                Log.d(TAG, "💾 Saved original wallpaper via $source (${bitmap!!.width}x${bitmap!!.height}) -> ${originalFile.absolutePath}")
            } else {
                Log.w(TAG, "💾 Could not read current wallpaper (likely a LIVE wallpaper or OS-restricted on this device) — unset() will clear to default")
            }
        } catch (e: Exception) {
            Log.w(TAG, "💾 Failed to save original wallpaper: ${e.message}")
        }
    }

    /**
     * Rasterise any Drawable (BitmapDrawable, gradient, color, layered, …) into a
     * Bitmap sized to the drawable's intrinsic size, or the screen size as a
     * fallback. Returns null only if it genuinely can't produce a bitmap.
     */
    private fun drawableToBitmap(context: Context, drawable: Drawable): Bitmap? {
        return try {
            if (drawable is BitmapDrawable && drawable.bitmap != null) {
                return drawable.bitmap
            }
            val metrics = context.resources.displayMetrics
            val width = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else metrics.widthPixels
            val height = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else metrics.heightPixels
            if (width <= 0 || height <= 0) return null
            val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
            bmp
        } catch (e: Exception) {
            Log.w(TAG, "💾 drawableToBitmap failed: ${e.message}")
            null
        }
    }

    /**
     * ♻️ Restore the previously-saved original wallpaper. Returns true if it was
     * restored, false if there was nothing saved (caller then clears to default).
     */
    private fun restoreOriginalWallpaper(context: Context): Boolean {
        val originalFile = File(context.filesDir, ORIGINAL_WALLPAPER_FILE)
        if (!originalFile.exists()) return false
        return try {
            val wallpaperManager = WallpaperManager.getInstance(context)
            val bitmap = BitmapFactory.decodeFile(originalFile.absolutePath)
            if (bitmap != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    wallpaperManager.setBitmap(
                        bitmap, null, true,
                        WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK
                    )
                } else {
                    @Suppress("DEPRECATION")
                    wallpaperManager.setBitmap(bitmap)
                }
                bitmap.recycle()
                originalFile.delete() // consume it — next set() saves a fresh original
                Log.d(TAG, "♻️ Restored customer's original wallpaper")
                true
            } else {
                originalFile.delete() // corrupt/undecodable — drop it
                false
            }
        } catch (e: Exception) {
            Log.w(TAG, "♻️ Failed to restore original wallpaper: ${e.message}")
            false
        }
    }
    
    /**
     * Download wallpaper from URL to local file
     */
    private suspend fun downloadWallpaper(context: Context): File? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(ApiConfig.Api.WALLPAPER_URL)
                .build()
            
            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.e(TAG, "❌ Failed to download wallpaper: ${response.code}")
                return@withContext null
            }
            
            val wallpaperFile = File(context.cacheDir, WALLPAPER_FILE_NAME)
            
            response.body?.byteStream()?.use { inputStream ->
                FileOutputStream(wallpaperFile).use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
            
            Log.d(TAG, "✅ Wallpaper downloaded to: ${wallpaperFile.absolutePath}")
            wallpaperFile
            
        } catch (e: IOException) {
            Log.e(TAG, "❌ Error downloading wallpaper: ${e.message}", e)
            null
        }
    }
    
    /**
     * Apply wallpaper from local file
     */
    private fun applyWallpaper(context: Context, wallpaperFile: File) {
        val wallpaperManager = WallpaperManager.getInstance(context)
        
        // Decode and crop bitmap to fit screen
        val bitmap = decodeAndCropBitmap(context, wallpaperFile)
        
        if (bitmap != null) {
            // Set wallpaper for both lock screen and home screen
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                wallpaperManager.setBitmap(bitmap, null, true, WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK)
            } else {
                @Suppress("DEPRECATION")
                wallpaperManager.setBitmap(bitmap)
            }
            
            bitmap.recycle()
            Log.d(TAG, "✅ Wallpaper applied successfully")
        } else {
            Log.e(TAG, "❌ Failed to decode wallpaper bitmap")
        }
    }
    
    /**
     * Decode and crop bitmap to match screen dimensions
     */
    private fun decodeAndCropBitmap(context: Context, wallpaperFile: File): Bitmap? {
        return try {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            
            BitmapFactory.decodeFile(wallpaperFile.absolutePath, options)
            
            val displayMetrics = context.resources.displayMetrics
            val screenWidth = displayMetrics.widthPixels
            val screenHeight = displayMetrics.heightPixels
            
            // Calculate sample size
            options.inSampleSize = calculateInSampleSize(options, screenWidth, screenHeight)
            options.inJustDecodeBounds = false
            
            val bitmap = BitmapFactory.decodeFile(wallpaperFile.absolutePath, options)
            
            if (bitmap != null) {
                // Crop and scale to fit screen
                cropAndScaleBitmap(bitmap, screenWidth, screenHeight)
            } else {
                null
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error decoding bitmap: ${e.message}", e)
            null
        }
    }
    
    /**
     * Calculate sample size for efficient bitmap loading
     */
    private fun calculateInSampleSize(
        options: BitmapFactory.Options,
        reqWidth: Int,
        reqHeight: Int
    ): Int {
        val height = options.outHeight
        val width = options.outWidth
        var inSampleSize = 1
        
        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2
            
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        
        return inSampleSize
    }
    
    /**
     * Crop and scale bitmap to fit screen dimensions
     */
    private fun cropAndScaleBitmap(originalBitmap: Bitmap, targetWidth: Int, targetHeight: Int): Bitmap {
        val originalWidth = originalBitmap.width
        val originalHeight = originalBitmap.height
        
        val aspectRatio = targetWidth.toFloat() / targetHeight.toFloat()
        val originalAspectRatio = originalWidth.toFloat() / originalHeight.toFloat()
        
        var cropWidth: Int
        var cropHeight: Int
        val cropX: Int
        val cropY: Int
        
        if (aspectRatio > originalAspectRatio) {
            // Target is wider - crop height
            cropHeight = originalHeight
            cropWidth = (originalHeight * aspectRatio).toInt()
            cropX = maxOf(0, (originalWidth - cropWidth) / 2)
            cropY = 0
        } else {
            // Target is taller - crop width
            cropWidth = originalWidth
            cropHeight = (originalWidth / aspectRatio).toInt()
            cropX = 0
            cropY = maxOf(0, (originalHeight - cropHeight) / 2)
        }
        
        // Ensure crop dimensions don't exceed original bitmap dimensions
        cropWidth = minOf(cropWidth, originalWidth - cropX)
        cropHeight = minOf(cropHeight, originalHeight - cropY)
        
        val croppedBitmap = Bitmap.createBitmap(originalBitmap, cropX, cropY, cropWidth, cropHeight)
        val scaledBitmap = Bitmap.createScaledBitmap(croppedBitmap, targetWidth, targetHeight, true)
        
        // Recycle intermediate bitmap
        if (croppedBitmap != originalBitmap) {
            croppedBitmap.recycle()
        }
        
        return scaledBitmap
    }
    
    /**
     * Clean up downloaded wallpaper file
     */
    private fun deleteWallpaperFile(context: Context) {
        try {
            val wallpaperFile = File(context.cacheDir, WALLPAPER_FILE_NAME)
            if (wallpaperFile.exists()) {
                wallpaperFile.delete()
                Log.d(TAG, "🗑️ Wallpaper file deleted")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error deleting wallpaper file: ${e.message}", e)
        }
    }
}




