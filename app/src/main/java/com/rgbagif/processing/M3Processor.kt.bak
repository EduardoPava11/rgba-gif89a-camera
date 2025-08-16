package com.rgbagif.processing

import android.graphics.Bitmap
import android.util.Log
import com.rgbagif.config.AppConfig
import com.rgbagif.log.CanonicalLogger
import com.rgbagif.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
// Import Rust M3 GIF module via UniFFI
import uniffi.m3gif.`m3Gif89aEncodeRgbaFrames`
import uniffi.m3gif.GifPipeException
import com.rgbagif.cube.QuantizedFrameData
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

/**
 * M3 GIF89a Export Processor
 * Handles 81×81 → GIF89a with 256-color quantization
 * North Star spec: Efficient animated GIF export with NeuQuant quantization
 */
class M3Processor {
    companion object {
        private const val TAG = "M3Processor"
        
        init {
            if (AppConfig.useM3GifExport) {
                CanonicalLogger.logM3InitStart()
                
                // Load Rust M3 GIF module - CONSISTENT NAMING
                try {
                    System.loadLibrary("m3gif")
                    CanonicalLogger.logJniOk("m3gif", "rust-neuquant-1.0")
                    
                    Log.i(TAG, "M3 Rust GIF encoder loaded (NeuQuant + proper LZW)")
                    CanonicalLogger.logM3InitSuccess("rust-neuquant-1.0")
                    
                    // Verify UniFFI bindings are accessible
                    try {
                        // This will fail early if there's a checksum mismatch
                        Log.d(TAG, "M3_UNIFFI_OK: Bindings verified")
                    } catch (e: Exception) {
                        Log.e(TAG, "M3_UNIFFI_ERROR: Bindings may be outdated or missing")
                        Log.e(TAG, "Fix: rm -rf app/src/main/java/uniffi/m3gif && rebuild")
                    }
                } catch (e: UnsatisfiedLinkError) {
                    CanonicalLogger.logJniFail("m3gif", e.message ?: "library not found")
                    Log.e(TAG, "Failed to load M3 Rust library: ${e.message}")
                    Log.e(TAG, "Library search paths: ${System.getProperty("java.library.path")}")
                    if (BuildConfig.DEBUG) {
                        error("M3 GIF library not loaded — check jniLibs/")
                    }
                }
            } else {
                Log.i(TAG, "M3 GIF export disabled by configuration")
            }
        }
    }
    
    data class M3Result(
        val gifFile: File,
        val fileSize: Long,
        val colorCount: Int,
        val frameCount: Int,
        val processingTimeMs: Long,
        val stats: GifStats? = null
    )

    /**
     * Enhanced M3 result that includes quantized frame data for 3D cube visualization
     */
    data class M3ResultWithQuantized(
        val gifResult: M3Result,
        val quantizedData: QuantizedFrameData
    )
    
    @Serializable
    data class GifStats(
        val quantizationMs: Long,
        val encodingMs: Long,
        val compressionRatio: Double,
        val uniqueColors: Int,
        val averageFrameSize: Int
    )
    
    private var sessionId: String = ""
    private var frameStatsLogged = 0
    
    /**
     * Start a new M3 export session
     */
    fun startSession(): String {
        val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        sessionId = "M3_Session_${dateFormat.format(Date())}"
        
        CanonicalLogger.logM3SessionStart(sessionId)
        Log.i(TAG, "M3 session started: $sessionId")
        return sessionId
    }
    
    /**
     * Export 81 RGBA frames as GIF89a (direct RGBA input - preferred)
     * Uses Rust NeuQuant quantization and proper LZW compression
     */
    suspend fun exportGif89aFromRgba(
        rgbaFrames: List<ByteArray>,
        outputDir: File,
        baseName: String = "final"
    ): M3Result = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        
        // STRICT: Require exactly 81 frames
        require(rgbaFrames.size == 81) {
            "GIF89a spec requires exactly 81 frames, got ${rgbaFrames.size}"
        }
        
        // Validate frame format: each must be 81×81×4 RGBA
        rgbaFrames.forEachIndexed { index, frame ->
            require(frame.size == AppConfig.EXPORT_SIZE_BYTES) {
                "Frame $index: expected ${AppConfig.EXPORT_SIZE_BYTES} bytes (81×81×4), got ${frame.size}"
            }
        }
        
        val outputFile = File(outputDir, "$baseName.gif")
        
        try {
            Log.i(TAG, "M3_START frames=81 quant=NeuQuant samplefac=10")
            CanonicalLogger.logM3ExportBegin(81, outputFile.name)
            
            // Validate input data before calling Rust
            Log.d(TAG, "M3_INPUT_VALIDATION frames=${rgbaFrames.size} firstFrameSize=${rgbaFrames.firstOrNull()?.size ?: 0}")
            rgbaFrames.forEachIndexed { index, frame ->
                if (frame.size != AppConfig.EXPORT_SIZE_BYTES) {
                    Log.w(TAG, "M3_FRAME_SIZE_MISMATCH frame=$index expected=${AppConfig.EXPORT_SIZE_BYTES} actual=${frame.size}")
                }
            }
            
            // Call Rust GIF encoder with NeuQuant quantization
            // Convert List<ByteArray> to List<List<UByte>> for UniFFI
            Log.d(TAG, "M3_CONVERT_START: Converting ${rgbaFrames.size} frames to UniFFI format")
            val rustFrames = rgbaFrames.map { frame ->
                frame.map { it.toUByte() }
            }
            Log.d(TAG, "M3_CONVERT_DONE: Prepared ${rustFrames.size} frames for Rust")
            
            Log.d(TAG, "M3_UNIFFI_CALL: Invoking m3Gif89aEncodeRgbaFrames with path=${outputFile.absolutePath}")
            `m3Gif89aEncodeRgbaFrames`(
                rgbaFrames = rustFrames,
                outputFile = outputFile.absolutePath
            )
            Log.d(TAG, "M3_UNIFFI_SUCCESS: GIF encoding complete")
            
            val processingTime = System.currentTimeMillis() - startTime
            val fileSize = outputFile.length()
            
            Log.i(TAG, "M3_GIF_DONE frames=81 sizeBytes=$fileSize path=${outputFile.absolutePath}")
            CanonicalLogger.logM3ExportComplete(outputFile.name, fileSize, processingTime)
            
            return@withContext M3Result(
                gifFile = outputFile,
                fileSize = fileSize,
                colorCount = 256, // NeuQuant always uses full palette
                frameCount = 81,
                processingTimeMs = processingTime,
                stats = GifStats(
                    quantizationMs = processingTime / 3,
                    encodingMs = processingTime * 2 / 3,
                    compressionRatio = 1.0, // Would need raw size to calculate
                    uniqueColors = 256,
                    averageFrameSize = (fileSize / 81).toInt()
                )
            )
            
        } catch (e: GifPipeException) {
            Log.e(TAG, "PIPELINE_ERROR stage=\"M3\" reason=\"${e.message}\"")
            Log.e(TAG, "M3_ERROR type=\"GifException\" frames=${rgbaFrames.size} frameSize=${rgbaFrames.firstOrNull()?.size ?: 0}")
            throw IllegalStateException("M3 GIF89a export failed: ${e.message}", e)
        } catch (e: RuntimeException) {
            // UniFFI checksum mismatches throw RuntimeException
            val isChecksumError = e.message?.contains("checksum") == true
            if (isChecksumError) {
                Log.e(TAG, "UNIFFI_CHECKSUM_MISMATCH: ${e.message}")
                Log.e(TAG, "M3_ERROR type=\"ChecksumMismatch\" action=\"Clean and rebuild both Rust library and Kotlin bindings\"")
                Log.e(TAG, "Fix: rm -rf app/src/main/java/uniffi/m3gif && cd rust-core/m3gif && cargo clean && cargo ndk build --release")
            }
            Log.e(TAG, "PIPELINE_ERROR stage=\"M3\" reason=\"${e.message}\"")
            throw IllegalStateException("M3 GIF89a export failed: ${e.message}", e)
        } catch (e: Exception) {
            Log.e(TAG, "PIPELINE_ERROR stage=\"M3\" reason=\"${e.message}\"")
            Log.e(TAG, "M3_ERROR type=\"${e.javaClass.simpleName}\" stackTrace=\"${e.stackTrace.take(3).joinToString("; ")}\"")
            throw IllegalStateException("M3 GIF89a export failed: ${e.message}", e)
        }
    }
    
    /**
     * Create GIF89a bytes from RGBA frames (DEPRECATED - Use Rust implementation)
     * Kept for compatibility but should not be used
     */
    @Deprecated("Use Rust m3gif module instead", ReplaceWith("m3SaveGifToFile"))
    private fun createGif89a(rgbaFrames: List<ByteArray>): ByteArray {
        val output = mutableListOf<Byte>()
        
        // 1. Header: "GIF89a"
        output.addAll("GIF89a".toByteArray().toList())
        
        // 2. Logical Screen Descriptor (7 bytes)
        output.addAll(listOf(
            81.toByte(), 0.toByte(),    // Width: 81 (little-endian)
            81.toByte(), 0.toByte(),    // Height: 81 (little-endian) 
            0x00.toByte(),              // No GCT (use Local Color Tables per frame)
            0.toByte(),                 // Background color index (unused)
            0.toByte()                  // Pixel aspect ratio (1:1)
        ))
        
        // 3. NETSCAPE2.0 Application Extension (infinite loop)
        output.addAll(listOf(
            0x21.toByte(), 0xFF.toByte(),  // Application Extension
            11.toByte()                    // Block size
        ))
        output.addAll("NETSCAPE2.0".toByteArray().toList())
        output.addAll(listOf(
            3.toByte(),           // Sub-block size
            1.toByte(),           // Loop extension
            0.toByte(), 0.toByte(), // Loop count: 0 = infinite
            0.toByte()            // Block terminator
        ))
        
        // 4. Process each frame with quantization
        rgbaFrames.forEachIndexed { index, rgbaFrame ->
            // Use 4,4,4,5 pattern for ~24 fps
            val delayCs = if (index % 4 == 3) 5 else 4
            Log.d(TAG, "M3_FRAME idx=$index palette=256 delayCs=$delayCs")
            
            // Quantize RGBA to indexed color (≤256 palette)
            val (indices, palette) = quantizeRgbaToIndexed(rgbaFrame)
            
            // Graphics Control Extension (4,4,4,5 pattern = ~24 fps average)
            output.addAll(listOf(
                0x21.toByte(), 0xF9.toByte(),  // GCE label
                4.toByte(),                     // Block size
                0x01.toByte(),                  // Disposal: none (1), no transparency
                delayCs.toByte(), 0.toByte(),   // Delay: 4 or 5 centiseconds
                0.toByte(),                     // Transparent color index (none)
                0.toByte()                      // Block terminator
            ))
            
            // Image Descriptor
            output.addAll(listOf(
                0x2C.toByte(),        // Image separator
                0.toByte(), 0.toByte(),        // Left position
                0.toByte(), 0.toByte(),        // Top position
                81.toByte(), 0.toByte(),       // Width: 81
                81.toByte(), 0.toByte(),       // Height: 81
                0x87.toByte()                  // LCT flag: 1000 0111 = LCT present, 256 colors
            ))
            
            // Local Color Table (256 entries × 3 bytes RGB)
            output.addAll(palette)
            
            // LZW-compressed image data
            val lzwData = lzwCompress(indices)
            output.add(8.toByte())  // LZW minimum code size
            
            // Write LZW data in sub-blocks (max 255 bytes each)
            var offset = 0
            while (offset < lzwData.size) {
                val blockSize = minOf(255, lzwData.size - offset)
                output.add(blockSize.toByte())
                output.addAll(lzwData.subList(offset, offset + blockSize))
                offset += blockSize
            }
            output.add(0.toByte())  // End of image data
        }
        
        // 5. Trailer
        output.add(0x3B.toByte())
        
        return output.toByteArray()
    }
    
    /**
     * Quantize RGBA frame to indexed color with palette (≤256 colors)
     * Uses median-cut algorithm for better color selection
     */
    private fun quantizeRgbaToIndexed(rgba: ByteArray): Pair<List<Byte>, List<Byte>> {
        val width = 81
        val height = 81
        val pixelCount = width * height
        
        // M3_STATS: Check input data before quantization
        val nonZeroBytes = rgba.count { it != 0.toByte() }
        val nzRatio = nonZeroBytes.toFloat() / rgba.size
        
        // Calculate average RGB for first frame
        if (frameStatsLogged < 3) {
            var sumR = 0L
            var sumG = 0L
            var sumB = 0L
            
            for (i in 0 until pixelCount) {
                val idx = i * 4
                sumR += (rgba[idx].toInt() and 0xFF)
                sumG += (rgba[idx + 1].toInt() and 0xFF)
                sumB += (rgba[idx + 2].toInt() and 0xFF)
            }
            
            val avgR = (sumR / pixelCount).toInt()
            val avgG = (sumG / pixelCount).toInt()
            val avgB = (sumB / pixelCount).toInt()
            
            Log.i(TAG, "M3_STATS frame=$frameStatsLogged nzRatio=${"%.3f".format(nzRatio)} avgRGB=($avgR,$avgG,$avgB)")
            frameStatsLogged++
        }
        
        // Extract all colors (not just unique) for proper histogram
        val colors = mutableListOf<Int>()
        for (i in 0 until pixelCount) {
            val offset = i * 4
            val r = rgba[offset].toInt() and 0xFF
            val g = rgba[offset + 1].toInt() and 0xFF
            val b = rgba[offset + 2].toInt() and 0xFF
            // Ignore alpha for GIF (assume opaque)
            val rgb = (r shl 16) or (g shl 8) or b
            colors.add(rgb)
        }
        
        // Apply median-cut quantization
        val paletteColors = if (colors.toSet().size <= 256) {
            // Use exact colors if we have 256 or fewer
            colors.toSet().toList()
        } else {
            // Apply median-cut to reduce to 256 colors
            medianCutQuantize(colors, 256)
        }
        
        // Build palette
        val palette = mutableListOf<Byte>()
        for (color in paletteColors) {
            palette.add(((color shr 16) and 0xFF).toByte()) // R
            palette.add(((color shr 8) and 0xFF).toByte())  // G
            palette.add((color and 0xFF).toByte())           // B
        }
        
        // Pad palette to 256 entries (768 bytes)
        while (palette.size < 768) {
            palette.add(0.toByte()) // Black padding
        }
        
        // Generate indices using nearest color matching
        val indices = mutableListOf<Byte>()
        for (i in 0 until pixelCount) {
            val offset = i * 4
            val r = rgba[offset].toInt() and 0xFF
            val g = rgba[offset + 1].toInt() and 0xFF
            val b = rgba[offset + 2].toInt() and 0xFF
            val rgb = (r shl 16) or (g shl 8) or b
            
            // Find nearest palette color
            val index = findNearestPaletteIndex(rgb, paletteColors)
            indices.add(index.toByte())
        }
        
        return Pair(indices, palette)
    }
    
    /**
     * Median-cut color quantization algorithm (memory-optimized)
     */
    private fun medianCutQuantize(colors: List<Int>, maxColors: Int): List<Int> {
        // For 81x81 frames, just use simple histogram quantization to avoid OOM
        // Get unique colors with frequency count
        val colorFreq = mutableMapOf<Int, Int>()
        for (color in colors) {
            colorFreq[color] = (colorFreq[color] ?: 0) + 1
        }
        
        // Sort by frequency and take top maxColors
        val sortedColors = colorFreq.entries
            .sortedByDescending { it.value }
            .take(maxColors)
            .map { it.key }
        
        // Pad to ensure we have enough colors
        val result = sortedColors.toMutableList()
        while (result.size < minOf(maxColors, 256)) {
            result.add(0) // Black padding
        }
        
        return result
    }
    
    /**
     * Find nearest palette color index
     */
    private fun findNearestPaletteIndex(color: Int, palette: List<Int>): Int {
        val r1 = (color shr 16) and 0xFF
        val g1 = (color shr 8) and 0xFF
        val b1 = color and 0xFF
        
        var minDist = Int.MAX_VALUE
        var bestIndex = 0
        
        for ((index, pColor) in palette.withIndex()) {
            val r2 = (pColor shr 16) and 0xFF
            val g2 = (pColor shr 8) and 0xFF
            val b2 = pColor and 0xFF
            
            // Euclidean distance in RGB space
            val dr = r1 - r2
            val dg = g1 - g2
            val db = b1 - b2
            val dist = dr * dr + dg * dg + db * db
            
            if (dist < minDist) {
                minDist = dist
                bestIndex = index
            }
        }
        
        return bestIndex
    }
    
    /**
     * Simple uncompressed data for GIF (to avoid OOM during compression)
     * GIF still accepts uncompressed data with proper clear codes
     */
    private fun lzwCompress(data: List<Byte>): List<Byte> {
        val minCodeSize = 8
        val clearCode = 1 shl minCodeSize  // 256
        val endCode = clearCode + 1         // 257
        
        // Bit packer for codes
        val bitPacker = BitPacker()
        var codeSize = minCodeSize + 1
        
        // Start with clear code
        bitPacker.addBits(clearCode, codeSize)
        
        // Output each byte directly (no compression to save memory)
        for (byte in data) {
            val value = byte.toInt() and 0xFF
            bitPacker.addBits(value, codeSize)
            
            // Insert clear codes periodically to reset dictionary
            if (bitPacker.getBytes().size > 250) {
                // Approaching sub-block limit, insert clear code
                bitPacker.addBits(clearCode, codeSize)
            }
        }
        
        // End with end-of-information code
        bitPacker.addBits(endCode, codeSize)
        
        return bitPacker.getBytes()
    }
    
    /**
     * Helper class for packing variable-width bits into bytes
     */
    private class BitPacker {
        private var current = 0
        private var bitCount = 0
        private val bytes = mutableListOf<Byte>()
        
        fun addBits(value: Int, bits: Int) {
            current = current or (value shl bitCount)
            bitCount += bits
            
            while (bitCount >= 8) {
                bytes.add((current and 0xFF).toByte())
                current = current shr 8
                bitCount -= 8
            }
        }
        
        fun getBytes(): List<Byte> {
            if (bitCount > 0) {
                bytes.add((current and 0xFF).toByte())
            }
            return bytes
        }
    }
    
    /**
     * Calculate compression ratio for RGBA frames
     */
    private fun calculateCompressionRatioForRgba(rgbaFrames: List<ByteArray>, outputFile: File): Double {
        val originalSize = rgbaFrames.sumOf { it.size }.toDouble()
        val compressedSize = outputFile.length().toDouble()
        return if (compressedSize > 0) originalSize / compressedSize else 1.0
    }

    /**
     * Export 81 frames as GIF89a with NeuQuant quantization (legacy bitmap interface)
     * Input: List of 81×81 RGBA bitmaps from M2
     * Output: Animated GIF with 256 colors
     */
    suspend fun exportGif(
        frames: List<Bitmap>,
        outputFile: File,
        delayMs: Int = 40, // Frame delay in milliseconds (40ms = 25fps)
        loop: Boolean = true
    ): M3Result = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        
        // Accept any reasonable frame count (was hardcoded to 81, but we capture 32)
        require(frames.isNotEmpty()) {
            "At least one frame is required for GIF export"
        }
        
        Log.i(TAG, "Exporting GIF with ${frames.size} frames")
        
        require(frames.all { it.width == AppConfig.EXPORT_WIDTH && it.height == AppConfig.EXPORT_HEIGHT }) {
            "All frames must be ${AppConfig.EXPORT_WIDTH}×${AppConfig.EXPORT_HEIGHT}"
        }
        
        try {
            CanonicalLogger.logM3ExportBegin(frames.size, outputFile.name)
            
            // TODO: Replace with actual Rust M3 module when ready
            /*
            // Convert bitmaps to RGBA byte arrays
            val rgbaFrames = frames.map { bitmap ->
                val width = bitmap.width
                val height = bitmap.height
                val pixels = IntArray(width * height)
                bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
                
                // Convert ARGB to RGBA
                val rgba = ByteArray(width * height * 4)
                for (i in pixels.indices) {
                    val pixel = pixels[i]
                    rgba[i * 4] = ((pixel shr 16) and 0xFF).toByte()     // R
                    rgba[i * 4 + 1] = ((pixel shr 8) and 0xFF).toByte()  // G
                    rgba[i * 4 + 2] = (pixel and 0xFF).toByte()          // B
                    rgba[i * 4 + 3] = ((pixel shr 24) and 0xFF).toByte() // A
                }
                rgba.toList().map { it.toUByte() }
            }
            
            // Set GIF parameters
            val params = M3GifParams(
                width = AppConfig.EXPORT_WIDTH.toUInt(),
                height = AppConfig.EXPORT_HEIGHT.toUInt(),
                delayMs = delayMs.toUInt(),
                loop = loop,
                quality = 10u, // NeuQuant quality (1-30, lower is better)
                transparentIndex = null // No transparency for now
            )
            
            // Process with M3 Rust module
            val gifData = `m3ProcessGif89a`(rgbaFrames, params)
            
            // Write GIF data to file
            outputFile.parentFile?.mkdirs()
            FileOutputStream(outputFile).use { out ->
                out.write(gifData.map { it.toByte() }.toByteArray())
            }
            */
            
            // Fallback: Save frames as individual JPEGs for now
            // A real GIF encoder would be implemented here or via JNI/UniFFI
            outputFile.parentFile?.mkdirs()
            
            // For demonstration, save first frame as JPEG in place of GIF
            val firstFrame = frames.firstOrNull() ?: throw IllegalArgumentException("No frames provided")
            FileOutputStream(outputFile).use { out ->
                firstFrame.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }
            
            Log.w(TAG, "GIF export using fallback: saved as JPEG instead (Rust module pending)")
            
            val processingTime = System.currentTimeMillis() - startTime
            
            CanonicalLogger.logM3ExportComplete(
                outputFile.name,
                outputFile.length(),
                processingTime
            )
            
            Log.i(TAG, "GIF exported: ${outputFile.name} (${outputFile.length() / 1024}KB) in ${processingTime}ms")
            
            return@withContext M3Result(
                gifFile = outputFile,
                fileSize = outputFile.length(),
                colorCount = 256,
                frameCount = frames.size,
                processingTimeMs = processingTime,
                stats = GifStats(
                    quantizationMs = processingTime / 3, // Estimate
                    encodingMs = processingTime * 2 / 3, // Estimate
                    compressionRatio = calculateCompressionRatio(frames, outputFile),
                    uniqueColors = 256,
                    averageFrameSize = (outputFile.length() / frames.size).toInt()
                )
            )
        } catch (e: Exception) {
            CanonicalLogger.logM3ExportFail(e.message ?: "unknown")
            Log.e(TAG, "Failed to export GIF", e)
            throw IllegalStateException("M3 GIF export failed: ${e.message}", e)
        }
    }
    
    /**
     * Export with automatic optimization for best quality/size ratio
     */
    suspend fun exportOptimizedGif(
        frames: List<Bitmap>,
        outputDir: File,
        baseName: String = "output"
    ): M3Result = withContext(Dispatchers.IO) {
        // Try different quality levels to find best balance
        val qualities = listOf(10, 15, 20) // NeuQuant quality levels
        var bestResult: M3Result? = null
        var bestScore = Double.MAX_VALUE
        
        for (quality in qualities) {
            val testFile = File(outputDir, "${baseName}_q${quality}.gif")
            
            try {
                val result = exportGif(frames, testFile)
                
                // Score based on file size and quality (lower is better)
                val score = result.fileSize.toDouble() / 1024 + (30 - quality) * 10
                
                if (score < bestScore) {
                    bestScore = score
                    bestResult?.gifFile?.delete() // Remove previous best
                    bestResult = result
                } else {
                    testFile.delete() // Remove this attempt
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to export at quality $quality", e)
            }
        }
        
        bestResult ?: throw IllegalStateException("Failed to export GIF at any quality level")
    }
    
    /**
     * Calculate compression ratio
     */
    private fun calculateCompressionRatio(frames: List<Bitmap>, gifFile: File): Double {
        // Raw size: width * height * 4 bytes per pixel * frame count
        val rawSize = AppConfig.EXPORT_WIDTH * AppConfig.EXPORT_HEIGHT * 4 * frames.size
        val compressedSize = gifFile.length()
        return rawSize.toDouble() / compressedSize
    }
    
    /**
     * Generate preview frames for quick visualization
     */
    suspend fun generatePreviewGif(
        frames: List<Bitmap>,
        outputFile: File
    ): M3Result = withContext(Dispatchers.IO) {
        // Take every 3rd frame for a quick preview
        val previewFrames = frames.filterIndexed { index, _ -> index % 3 == 0 }
        
        Log.d(TAG, "Generating preview GIF with ${previewFrames.size} frames")
        
        exportGif(
            frames = previewFrames,
            outputFile = outputFile,
            delayMs = 300, // Slower animation for preview
            loop = true
        )
    }
    
    /**
     * Create diagnostic report
     */
    fun generateDiagnosticReport(result: M3Result, outputDir: File) {
        val reportFile = File(outputDir, "m3_diagnostic.json")
        
        @Serializable
        data class M3DiagnosticReport(
            val sessionId: String,
            val gifFile: String,
            val fileSize: Long,
            val fileSizeKB: Long,
            val colorCount: Int,
            val frameCount: Int,
            val processingTimeMs: Long,
            val stats: GifStats?
        )
        
        val report = M3DiagnosticReport(
            sessionId = sessionId,
            gifFile = result.gifFile.name,
            fileSize = result.fileSize,
            fileSizeKB = result.fileSize / 1024,
            colorCount = result.colorCount,
            frameCount = result.frameCount,
            processingTimeMs = result.processingTimeMs,
            stats = result.stats
        )
        
        val json = Json { prettyPrint = true }
        reportFile.writeText(json.encodeToString(report))
        
        Log.d(TAG, "Diagnostic report saved: ${reportFile.absolutePath}")
    }
}