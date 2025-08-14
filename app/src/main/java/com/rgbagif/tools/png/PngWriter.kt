package com.rgbagif.tools.png

import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer

/**
 * PNG writer utility for converting RGBA data to PNG files.
 * Centralizes all PNG writing logic to ensure consistency.
 */
object PngWriter {
    private const val TAG = "PngWriter"
    
    /**
     * Write RGBA byte array to PNG file
     * @param rgba RGBA byte array (4 bytes per pixel)
     * @param w Width in pixels
     * @param h Height in pixels  
     * @param out Output PNG file
     * @return true if successful
     */
    @JvmStatic
    fun rgbaToPng(rgba: ByteArray, w: Int, h: Int, out: File): Boolean {
        require(rgba.size == w * h * 4) {
            "RGBA array size mismatch: got ${rgba.size}, expected ${w * h * 4}"
        }
        
        return try {
            // Convert RGBA to ARGB for Android Bitmap
            val pixels = rgbaToArgb(rgba)
            
            // Create bitmap
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
            
            // Write PNG (quality parameter is ignored for PNG)
            out.parentFile?.mkdirs()
            FileOutputStream(out).use { stream ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            }
            
            bitmap.recycle()
            
            Log.d(TAG, "Wrote PNG: ${out.name} (${w}×${h})")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write PNG: ${out.name}", e)
            false
        }
    }
    
    /**
     * Write RGBA byte array to PNG file (suspend version)
     */
    @JvmStatic
    suspend fun rgbaToPngSuspend(
        rgba: ByteArray, 
        w: Int, 
        h: Int, 
        out: File
    ): Boolean = withContext(Dispatchers.IO) {
        rgbaToPng(rgba, w, h, out)
    }
    
    /**
     * Convert Bitmap to PNG file
     */
    @JvmStatic
    fun bitmapToPng(bitmap: Bitmap, out: File): Boolean {
        return try {
            out.parentFile?.mkdirs()
            FileOutputStream(out).use { stream ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            }
            
            Log.d(TAG, "Wrote PNG from bitmap: ${out.name} (${bitmap.width}×${bitmap.height})")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write PNG from bitmap: ${out.name}", e)
            false
        }
    }
    
    /**
     * Convert RGBA bytes to ARGB int array
     */
    private fun rgbaToArgb(rgba: ByteArray): IntArray {
        val pixelCount = rgba.size / 4
        val pixels = IntArray(pixelCount)
        val buffer = ByteBuffer.wrap(rgba)
        
        for (i in pixels.indices) {
            val r = buffer.get().toInt() and 0xFF
            val g = buffer.get().toInt() and 0xFF
            val b = buffer.get().toInt() and 0xFF
            val a = buffer.get().toInt() and 0xFF
            
            // Pack as ARGB
            pixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
        
        return pixels
    }
    
    /**
     * Extract RGBA bytes from Bitmap
     */
    @JvmStatic
    fun bitmapToRgba(bitmap: Bitmap): ByteArray {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        
        val rgba = ByteArray(pixels.size * 4)
        var index = 0
        
        for (pixel in pixels) {
            // Extract ARGB and convert to RGBA
            rgba[index++] = ((pixel shr 16) and 0xFF).toByte() // R
            rgba[index++] = ((pixel shr 8) and 0xFF).toByte()  // G
            rgba[index++] = (pixel and 0xFF).toByte()          // B
            rgba[index++] = ((pixel shr 24) and 0xFF).toByte() // A
        }
        
        return rgba
    }
}