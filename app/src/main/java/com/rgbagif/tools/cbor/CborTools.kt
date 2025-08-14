package com.rgbagif.tools.cbor

import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer

/**
 * CBOR tools for encoding/decoding frame data.
 * This is the source of truth for frame serialization.
 */
object CborTools {
    private const val TAG = "CborTools"
    
    // Configure CBOR with definite length encoding (optional)
    private val cbor = Cbor {
        encodeDefaults = true
    }
    
    /**
     * Decode CBOR file to RgbaFrame
     */
    @JvmStatic
    fun decodeFrame(input: File): RgbaFrame {
        require(input.exists()) { "CBOR file does not exist: ${input.absolutePath}" }
        
        val bytes = input.readBytes()
        return decodeFrame(bytes)
    }
    
    /**
     * Decode CBOR bytes to RgbaFrame
     * Handles both standard format and M1Fast JNI format
     */
    @JvmStatic
    fun decodeFrame(bytes: ByteArray): RgbaFrame {
        return try {
            // Try standard format first
            val frame = cbor.decodeFromByteArray<RgbaFrame>(bytes)
            require(frame.validate()) { 
                "Invalid frame data: size=${frame.rgba.size}, expected=${frame.expectedByteSize}" 
            }
            frame
        } catch (e: Exception) {
            // If standard format fails, try M1Fast format with short field names
            try {
                decodeM1FastFormat(bytes)
            } catch (e2: Exception) {
                Log.e(TAG, "Failed to decode CBOR frame in either format", e2)
                throw e2
            }
        }
    }
    
    /**
     * Decode CBOR from M1Fast JNI which uses short field names
     * M1Fast format: {"w": 729, "h": 729, "format": "RGBA8888", "data": bytes, "ts_ms": long, "frame_index": int}
     */
    @JvmStatic
    private fun decodeM1FastFormat(bytes: ByteArray): RgbaFrame {
        val cborObject = com.upokecenter.cbor.CBORObject.DecodeFromBytes(bytes)
        
        val width = cborObject["w"]?.AsInt32() ?: throw IllegalArgumentException("Missing 'w' field")
        val height = cborObject["h"]?.AsInt32() ?: throw IllegalArgumentException("Missing 'h' field")
        val format = cborObject["format"]?.AsString() ?: "RGBA8888"
        val dataBytes = cborObject["data"]?.GetByteString() ?: throw IllegalArgumentException("Missing 'data' field")
        val timestampMs = cborObject["ts_ms"]?.AsInt64() ?: System.currentTimeMillis()
        val frameIndex = cborObject["frame_index"]?.AsInt32() ?: 0
        
        return RgbaFrame(
            width = width,
            height = height,
            format = if (format == "RGBA8888") "RGBA_8888" else format,
            rgba = dataBytes,
            timestampMs = timestampMs,
            meta = mapOf("frameIndex" to frameIndex.toString())
        )
    }
    
    /**
     * Encode RgbaFrame to CBOR bytes
     */
    @JvmStatic
    fun encodeFrame(frame: RgbaFrame): ByteArray {
        require(frame.validate()) { 
            "Invalid frame data before encoding: size=${frame.rgba.size}, expected=${frame.expectedByteSize}" 
        }
        
        return try {
            cbor.encodeToByteArray(frame)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to encode frame to CBOR", e)
            throw e
        }
    }
    
    /**
     * Decode CBOR file and save as PNG
     */
    @JvmStatic
    suspend fun decodeToPng(input: File, outPng: File): Boolean = withContext(Dispatchers.IO) {
        try {
            val frame = decodeFrame(input)
            rgbaToPng(frame.rgba, frame.width, frame.height, outPng)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode CBOR to PNG: ${input.name}", e)
            false
        }
    }
    
    /**
     * Decode CBOR file and save as JPEG
     */
    @JvmStatic
    suspend fun decodeToJpeg(input: File, outJpeg: File, quality: Int = 95): Boolean = withContext(Dispatchers.IO) {
        try {
            val frame = decodeFrame(input)
            rgbaToJpeg(frame.rgba, frame.width, frame.height, outJpeg, quality)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode CBOR to JPEG: ${input.name}", e)
            false
        }
    }
    
    /**
     * Convert RGBA byte array to PNG file
     */
    @JvmStatic
    fun rgbaToPng(rgba: ByteArray, width: Int, height: Int, out: File): Boolean {
        return try {
            // Convert RGBA bytes to ARGB int array for Bitmap
            val pixels = IntArray(width * height)
            val buffer = ByteBuffer.wrap(rgba)
            
            for (i in pixels.indices) {
                val r = buffer.get().toInt() and 0xFF
                val g = buffer.get().toInt() and 0xFF
                val b = buffer.get().toInt() and 0xFF
                val a = buffer.get().toInt() and 0xFF
                
                // Pack as ARGB
                pixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
            
            // Create bitmap and save as PNG
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
            
            FileOutputStream(out).use { stream ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            }
            
            bitmap.recycle()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to convert RGBA to PNG", e)
            false
        }
    }
    
    /**
     * Convert RGBA byte array to JPEG file
     * JPEG doesn't support transparency, so alpha channel is ignored
     */
    @JvmStatic
    fun rgbaToJpeg(rgba: ByteArray, width: Int, height: Int, out: File, quality: Int = 95): Boolean {
        return try {
            // Convert RGBA bytes to RGB (discard alpha) for JPEG
            val pixels = IntArray(width * height)
            val buffer = ByteBuffer.wrap(rgba)
            
            for (i in pixels.indices) {
                val r = buffer.get().toInt() and 0xFF
                val g = buffer.get().toInt() and 0xFF
                val b = buffer.get().toInt() and 0xFF
                buffer.get() // Skip alpha channel for JPEG
                
                // Pack as RGB with full alpha (JPEG will ignore alpha)
                pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
            
            // Create bitmap and save as JPEG
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
            
            FileOutputStream(out).use { stream ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
            }
            
            bitmap.recycle()
            Log.d(TAG, "Saved JPEG: ${out.name} (${out.length() / 1024}KB)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to convert RGBA to JPEG", e)
            false
        }
    }
    
    /**
     * Batch decode all CBOR files in a directory to PNG
     */
    @JvmStatic
    suspend fun decodeAllToPng(
        cborDir: File, 
        pngDir: File, 
        force: Boolean = false
    ): List<File> = withContext(Dispatchers.IO) {
        if (!cborDir.exists()) {
            Log.w(TAG, "CBOR directory does not exist: ${cborDir.absolutePath}")
            return@withContext emptyList()
        }
        
        if (!pngDir.exists()) {
            pngDir.mkdirs()
        }
        
        val cborFiles = cborDir.listFiles { file ->
            file.extension == "cbor"
        }?.sortedBy { it.name } ?: emptyList()
        
        val pngFiles = mutableListOf<File>()
        
        for (cborFile in cborFiles) {
            val pngFile = File(pngDir, cborFile.nameWithoutExtension + ".png")
            
            // Skip if PNG already exists and not forcing
            if (!force && pngFile.exists()) {
                pngFiles.add(pngFile)
                continue
            }
            
            if (decodeToPng(cborFile, pngFile)) {
                pngFiles.add(pngFile)
                Log.d(TAG, "Decoded ${cborFile.name} -> ${pngFile.name}")
            }
        }
        
        Log.i(TAG, "Decoded ${pngFiles.size}/${cborFiles.size} CBOR files to PNG")
        pngFiles
    }
    
    /**
     * Batch decode all CBOR files in a directory to JPEG
     * Use this instead of PNG for better compatibility and smaller file sizes
     */
    @JvmStatic
    suspend fun decodeAllToJpeg(
        cborDir: File,
        jpegDir: File,
        quality: Int = 95,
        force: Boolean = false
    ): List<File> = withContext(Dispatchers.IO) {
        if (!cborDir.exists()) {
            Log.w(TAG, "CBOR directory does not exist: ${cborDir.absolutePath}")
            return@withContext emptyList()
        }
        
        if (!jpegDir.exists()) {
            jpegDir.mkdirs()
        }
        
        val cborFiles = cborDir.listFiles { file ->
            file.extension == "cbor"
        }?.sortedBy { it.name } ?: emptyList()
        
        val jpegFiles = mutableListOf<File>()
        
        for (cborFile in cborFiles) {
            val jpegFile = File(jpegDir, cborFile.nameWithoutExtension + ".jpg")
            
            // Skip if JPEG already exists and not forcing
            if (!force && jpegFile.exists()) {
                jpegFiles.add(jpegFile)
                continue
            }
            
            if (decodeToJpeg(cborFile, jpegFile, quality)) {
                jpegFiles.add(jpegFile)
                Log.d(TAG, "Decoded ${cborFile.name} -> ${jpegFile.name} (${jpegFile.length() / 1024}KB)")
            }
        }
        
        Log.i(TAG, "Decoded ${jpegFiles.size}/${cborFiles.size} CBOR files to JPEG")
        jpegFiles
    }
    
    /**
     * Create RgbaFrame from camera capture data
     */
    @JvmStatic
    fun createFrame(
        rgbaData: ByteArray,
        width: Int,
        height: Int,
        timestampMs: Long,
        meta: Map<String, String> = emptyMap()
    ): RgbaFrame {
        return RgbaFrame(
            width = width,
            height = height,
            format = "RGBA_8888",
            rgba = rgbaData,
            timestampMs = timestampMs,
            meta = meta
        )
    }
    
    /**
     * Validate CBOR file structure
     */
    @JvmStatic
    fun validateCborFile(file: File): Boolean {
        return try {
            val frame = decodeFrame(file)
            frame.validate()
        } catch (e: Exception) {
            Log.e(TAG, "Invalid CBOR file: ${file.name}", e)
            false
        }
    }
}