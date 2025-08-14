package com.rgbagif.processing

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Builds a 9×9 mosaic of 81 frames for quick visual QA
 * Each frame is 81×81, resulting in a 729×729 mosaic
 */
class MosaicBuilder {
    companion object {
        private const val TAG = "MosaicBuilder"
        private const val GRID_SIZE = 9
        private const val FRAME_SIZE = 81
        private const val MOSAIC_SIZE = GRID_SIZE * FRAME_SIZE // 729×729
    }
    
    /**
     * Generate mosaic from PNG files
     */
    suspend fun generateMosaic(
        pngDir: File,
        outputFile: File
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val startTime = System.currentTimeMillis()
            
            // Get all PNG files
            val pngFiles = pngDir.listFiles { file ->
                file.extension.equals("png", ignoreCase = true)
            }?.sortedBy { it.name }?.toTypedArray() ?: emptyArray()
            
            if (pngFiles.isEmpty()) {
                Log.w(TAG, "No PNG files found in $pngDir")
                return@withContext false
            }
            
            // Create mosaic bitmap
            val mosaicBitmap = Bitmap.createBitmap(
                MOSAIC_SIZE,
                MOSAIC_SIZE,
                Bitmap.Config.ARGB_8888
            )
            
            val canvas = Canvas(mosaicBitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            
            // Draw each frame in the grid
            var frameIndex = 0
            for (row in 0 until GRID_SIZE) {
                for (col in 0 until GRID_SIZE) {
                    if (frameIndex < pngFiles.size) {
                        val frameBitmap = BitmapFactory.decodeFile(pngFiles[frameIndex].absolutePath)
                        
                        if (frameBitmap != null) {
                            // Calculate position in mosaic
                            val x = col * FRAME_SIZE
                            val y = row * FRAME_SIZE
                            
                            // Draw frame
                            canvas.drawBitmap(
                                frameBitmap,
                                x.toFloat(),
                                y.toFloat(),
                                paint
                            )
                            
                            frameBitmap.recycle()
                        } else {
                            Log.w(TAG, "Failed to decode ${pngFiles[frameIndex].name}")
                        }
                        
                        frameIndex++
                    }
                }
            }
            
            // Save mosaic as PNG
            FileOutputStream(outputFile).use { out ->
                mosaicBitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            
            mosaicBitmap.recycle()
            
            val elapsed = System.currentTimeMillis() - startTime
            Log.i(TAG, "Generated mosaic with ${pngFiles.size} frames in ${elapsed}ms")
            Log.i(TAG, "Mosaic saved to: ${outputFile.absolutePath}")
            
            return@withContext true
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate mosaic", e)
            return@withContext false
        }
    }
    
    /**
     * Generate mosaic from bitmaps in memory
     */
    suspend fun generateMosaicFromBitmaps(
        bitmaps: List<Bitmap>,
        outputFile: File
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            if (bitmaps.isEmpty()) {
                Log.w(TAG, "No bitmaps provided")
                return@withContext false
            }
            
            val startTime = System.currentTimeMillis()
            
            // Create mosaic bitmap
            val mosaicBitmap = Bitmap.createBitmap(
                MOSAIC_SIZE,
                MOSAIC_SIZE,
                Bitmap.Config.ARGB_8888
            )
            
            val canvas = Canvas(mosaicBitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            
            // Draw each frame in the grid
            var frameIndex = 0
            for (row in 0 until GRID_SIZE) {
                for (col in 0 until GRID_SIZE) {
                    if (frameIndex < bitmaps.size) {
                        val frameBitmap = bitmaps[frameIndex]
                        
                        // Calculate position in mosaic
                        val x = col * FRAME_SIZE
                        val y = row * FRAME_SIZE
                        
                        // Draw frame (scale if needed)
                        if (frameBitmap.width == FRAME_SIZE && frameBitmap.height == FRAME_SIZE) {
                            canvas.drawBitmap(
                                frameBitmap,
                                x.toFloat(),
                                y.toFloat(),
                                paint
                            )
                        } else {
                            // Scale to fit
                            val scaledBitmap = Bitmap.createScaledBitmap(
                                frameBitmap,
                                FRAME_SIZE,
                                FRAME_SIZE,
                                true
                            )
                            canvas.drawBitmap(
                                scaledBitmap,
                                x.toFloat(),
                                y.toFloat(),
                                paint
                            )
                            if (scaledBitmap != frameBitmap) {
                                scaledBitmap.recycle()
                            }
                        }
                        
                        frameIndex++
                    }
                }
            }
            
            // Save mosaic as PNG
            FileOutputStream(outputFile).use { out ->
                mosaicBitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            
            mosaicBitmap.recycle()
            
            val elapsed = System.currentTimeMillis() - startTime
            Log.i(TAG, "Generated mosaic from ${bitmaps.size} bitmaps in ${elapsed}ms")
            
            return@withContext true
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate mosaic from bitmaps", e)
            return@withContext false
        }
    }
}