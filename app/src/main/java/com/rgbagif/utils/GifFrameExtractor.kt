package com.rgbagif.utils

import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.os.Build
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Utility for extracting quantized frames from GIF files
 */
object GifFrameExtractor {
    
    /**
     * Extracts frames from a GIF file as ImageBitmaps
     * These are the quantized frames after NeuQuant processing
     */
    suspend fun extractFrames(gifFile: File): List<ImageBitmap> = withContext(Dispatchers.IO) {
        try {
            if (!gifFile.exists()) {
                return@withContext emptyList()
            }
            
            // For now, we'll extract frames using Android's ImageDecoder (API 28+)
            // or fall back to a simple approach for older APIs
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                extractFramesWithImageDecoder(gifFile)
            } else {
                // For older APIs, we'll just load the first frame
                // Full GIF frame extraction would require a third-party library
                extractFirstFrame(gifFile)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }
    
    /**
     * Extract frames using ImageDecoder (API 28+)
     */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.P)
    private fun extractFramesWithImageDecoder(gifFile: File): List<ImageBitmap> {
        val frames = mutableListOf<ImageBitmap>()
        
        try {
            val source = ImageDecoder.createSource(gifFile)
            val drawable = ImageDecoder.decodeDrawable(source)
            
            // If it's an animated drawable, we can get frames
            if (drawable is android.graphics.drawable.AnimatedImageDrawable) {
                // Note: This is a simplified approach
                // Full frame extraction would require more complex handling
                drawable.start()
                
                // For demonstration, we'll capture the current frame
                // In a real implementation, you'd iterate through all frames
                val bitmap = android.graphics.Bitmap.createBitmap(
                    drawable.intrinsicWidth,
                    drawable.intrinsicHeight,
                    android.graphics.Bitmap.Config.ARGB_8888
                )
                val canvas = android.graphics.Canvas(bitmap)
                drawable.draw(canvas)
                frames.add(bitmap.asImageBitmap())
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        return frames
    }
    
    /**
     * Extract just the first frame for older APIs
     */
    private fun extractFirstFrame(gifFile: File): List<ImageBitmap> {
        return try {
            val bitmap = BitmapFactory.decodeFile(gifFile.absolutePath)
            if (bitmap != null) {
                listOf(bitmap.asImageBitmap())
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }
    
    /**
     * Creates placeholder frames for demonstration
     * These would normally come from the actual quantized data
     */
    fun createPlaceholderFrames(count: Int = 81): List<ImageBitmap> {
        val frames = mutableListOf<ImageBitmap>()
        
        for (i in 0 until count) {
            // Create a simple colored bitmap as placeholder
            val bitmap = android.graphics.Bitmap.createBitmap(
                81, 81, 
                android.graphics.Bitmap.Config.ARGB_8888
            )
            
            // Fill with a gradient color based on frame index
            val canvas = android.graphics.Canvas(bitmap)
            val hue = (i * 360f / count)
            val color = android.graphics.Color.HSVToColor(
                floatArrayOf(hue, 0.8f, 0.9f)
            )
            canvas.drawColor(color)
            
            // Add frame number
            val paint = android.graphics.Paint().apply {
                this.color = android.graphics.Color.WHITE
                textSize = 20f
                textAlign = android.graphics.Paint.Align.CENTER
            }
            canvas.drawText(
                i.toString(),
                40f, 45f,
                paint
            )
            
            frames.add(bitmap.asImageBitmap())
        }
        
        return frames
    }
}