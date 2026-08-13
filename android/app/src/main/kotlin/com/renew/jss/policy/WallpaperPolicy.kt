package com.renew.jss.policy

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.util.DisplayMetrics
import android.util.Log
import com.renew.jss.ApiConfig
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
    private const val PREF_WALLPAPER_LOCKED = "wallpaper_locked"
    private const val PREFS_NAME = "wallpaper_prefs"

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
        Log.d(TAG, "🗑️ Removing wallpaper")
        
        try {
            val wallpaperManager = WallpaperManager.getInstance(context)
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                wallpaperManager.clearWallpaper()
            } else {
                @Suppress("DEPRECATION")
                wallpaperManager.clear()
            }
            
            // Clean up downloaded wallpaper file
            deleteWallpaperFile(context)
            
            // 🔓 Unlock wallpaper changes
            setLockedState(context, false)
            
            Log.d(TAG, "✅ Wallpaper removed successfully")
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error removing wallpaper: ${e.message}", e)
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




