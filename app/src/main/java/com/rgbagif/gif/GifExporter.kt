package com.rgbagif.gif

import android.graphics.Color
import android.os.Trace
import com.rgbagif.log.LogEvent
import com.rgbagif.settings.DevSettings
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream

/**
 * GIF89a exporter with global palette support
 * Implements the GIF89a specification for animated GIFs
 */
class GifExporter {
    
    companion object {
        private const val TAG = "GifExporter"
        
        // GIF89a constants
        private const val GIF_HEADER = "GIF89a"
        private const val TRANSPARENT_INDEX: Byte = 0
        private const val DEFAULT_DELAY_CS = 10 // 10 centiseconds = 100ms
    }
    
    /**
     * Export frames to GIF89a format
     * 
     * @param frames RGBA frames to export
     * @param outputFile Target GIF file
     * @param maxColors Maximum palette colors (256 for GIF)
     * @param frameDelayMs Delay between frames in milliseconds
     * @return Export result with metrics
     */
    suspend fun exportGif(
        frames: List<RgbaFrame>,
        outputFile: File,
        maxColors: Int = 256,
        frameDelayMs: Int = 100
    ): GifExportResult {
        Trace.beginSection("GIF89a_EXPORT")
        val startTime = System.currentTimeMillis()
        
        try {
            // Choose quantizer based on settings
            val quantizer = when (DevSettings.quantizerType.value) {
                DevSettings.QuantizerType.MEDIAN_CUT -> MedianCutQuantizer()
                DevSettings.QuantizerType.OCTREE -> OctreeQuantizer()
                DevSettings.QuantizerType.NEUQUANT -> {
                    // NeuQuant not implemented yet, fall back to median-cut
                    Timber.tag(TAG).w("NeuQuant not implemented, using MedianCut")
                    MedianCutQuantizer()
                }
            }
            
            val enableDithering = DevSettings.enableDithering.value
            
            // Quantize all frames to global palette
            Trace.beginSection("QUANTIZATION")
            Timber.tag(TAG).d("Quantizing ${frames.size} frames with ${quantizer.name}, dithering=$enableDithering")
            val quantizationResult = quantizer.quantize(frames, maxColors, enableDithering)
            Trace.endSection()
            
            // Write GIF file
            Trace.beginSection("GIF_WRITE")
            val gifBytes = buildGif(
                quantizationResult,
                frames.first().width,
                frames.first().height,
                frameDelayMs
            )
            Trace.endSection()
            
            outputFile.writeBytes(gifBytes)
            
            val exportTime = System.currentTimeMillis() - startTime
            
            // Log metrics
            LogEvent.Entry(
                event = "gif_export_complete",
                milestone = "M3",
                sessionId = "",
                extra = mapOf(
                    "quantizer" to quantizer.name,
                    "dithering" to enableDithering,
                    "palette_size" to quantizationResult.palette.size,
                    "frame_count" to frames.size,
                    "export_time_ms" to exportTime,
                    "file_size_bytes" to outputFile.length()
                )
            ).log()
            
            return GifExportResult(
                success = true,
                outputFile = outputFile,
                paletteSize = quantizationResult.palette.size,
                fileSize = outputFile.length(),
                exportTimeMs = exportTime,
                quantizer = quantizer.name,
                ditheringUsed = enableDithering
            )
            
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "GIF export failed")
            return GifExportResult(
                success = false,
                error = e.message
            )
        } finally {
            Trace.endSection()
        }
    }
    
    /**
     * Build GIF89a byte array from quantized frames
     */
    private fun buildGif(
        quantizationResult: QuantizationResult,
        width: Int,
        height: Int,
        frameDelayMs: Int
    ): ByteArray {
        val output = ByteArrayOutputStream()
        
        // Write GIF header
        writeHeader(output)
        
        // Write logical screen descriptor with global color table
        writeLogicalScreenDescriptor(output, width, height, quantizationResult.palette)
        
        // Write global color table
        writeColorTable(output, quantizationResult.palette)
        
        // Write application extension for looping
        writeApplicationExtension(output)
        
        // Write frames
        quantizationResult.indexedFrames.forEachIndexed { index, frame ->
            writeFrame(output, frame, frameDelayMs, index == 0)
        }
        
        // Write trailer
        output.write(0x3B) // GIF trailer
        
        return output.toByteArray()
    }
    
    private fun writeHeader(output: OutputStream) {
        output.write(GIF_HEADER.toByteArray())
    }
    
    private fun writeLogicalScreenDescriptor(
        output: OutputStream,
        width: Int,
        height: Int,
        palette: IntArray
    ) {
        // Width (2 bytes, little-endian)
        output.write(width and 0xFF)
        output.write((width shr 8) and 0xFF)
        
        // Height (2 bytes, little-endian)
        output.write(height and 0xFF)
        output.write((height shr 8) and 0xFF)
        
        // Packed fields
        val globalColorTableFlag = 1 // Using global color table
        val colorResolution = 7 // 8 bits per channel
        val sortFlag = 0 // Not sorted
        val globalColorTableSize = getPaletteSize(palette.size)
        
        val packed = (globalColorTableFlag shl 7) or
                    (colorResolution shl 4) or
                    (sortFlag shl 3) or
                    globalColorTableSize
        
        output.write(packed)
        
        // Background color index
        output.write(0)
        
        // Pixel aspect ratio
        output.write(0)
    }
    
    private fun writeColorTable(output: OutputStream, palette: IntArray) {
        // Write palette colors
        palette.forEach { color ->
            output.write(Color.red(color))
            output.write(Color.green(color))
            output.write(Color.blue(color))
        }
        
        // Pad to power of 2 if needed
        val targetSize = 1 shl (getPaletteSize(palette.size) + 1)
        for (i in palette.size until targetSize) {
            output.write(0)
            output.write(0)
            output.write(0)
        }
    }
    
    private fun writeApplicationExtension(output: OutputStream) {
        // Extension introducer
        output.write(0x21)
        // Application extension label
        output.write(0xFF)
        // Block size
        output.write(11)
        // Application identifier (NETSCAPE2.0)
        output.write("NETSCAPE2.0".toByteArray())
        // Sub-block size
        output.write(3)
        // Sub-block ID
        output.write(1)
        // Loop count (0 = infinite)
        output.write(0)
        output.write(0)
        // Block terminator
        output.write(0)
    }
    
    private fun writeFrame(
        output: OutputStream,
        frame: IndexedFrame,
        frameDelayMs: Int,
        isFirst: Boolean
    ) {
        // Write graphics control extension
        writeGraphicsControlExtension(output, frameDelayMs)
        
        // Write image descriptor
        writeImageDescriptor(output, frame.width, frame.height)
        
        // Write image data (LZW compressed)
        writeImageData(output, frame.indices)
    }
    
    private fun writeGraphicsControlExtension(output: OutputStream, frameDelayMs: Int) {
        // Extension introducer
        output.write(0x21)
        // Graphic control label
        output.write(0xF9)
        // Block size
        output.write(4)
        
        // Packed fields
        val disposalMethod = 1 // Do not dispose
        val userInputFlag = 0
        val transparentColorFlag = 0 // No transparency for now
        val packed = (disposalMethod shl 2) or (userInputFlag shl 1) or transparentColorFlag
        output.write(packed)
        
        // Delay time in centiseconds
        val delayCs = frameDelayMs / 10
        output.write(delayCs and 0xFF)
        output.write((delayCs shr 8) and 0xFF)
        
        // Transparent color index
        output.write(0)
        
        // Block terminator
        output.write(0)
    }
    
    private fun writeImageDescriptor(
        output: OutputStream,
        width: Int,
        height: Int
    ) {
        // Image separator
        output.write(0x2C)
        
        // Image position (top-left = 0,0)
        output.write(0)
        output.write(0)
        output.write(0)
        output.write(0)
        
        // Image dimensions
        output.write(width and 0xFF)
        output.write((width shr 8) and 0xFF)
        output.write(height and 0xFF)
        output.write((height shr 8) and 0xFF)
        
        // Packed fields (no local color table)
        output.write(0)
    }
    
    private fun writeImageData(output: OutputStream, indices: ByteArray) {
        // LZW minimum code size (8 bits for 256 colors)
        val minCodeSize = 8
        output.write(minCodeSize)
        
        // Compress indices using LZW
        val compressed = lzwCompress(indices, minCodeSize)
        
        // Write compressed data in sub-blocks
        var offset = 0
        while (offset < compressed.size) {
            val blockSize = minOf(255, compressed.size - offset)
            output.write(blockSize)
            output.write(compressed, offset, blockSize)
            offset += blockSize
        }
        
        // Block terminator
        output.write(0)
    }
    
    /**
     * Simple LZW compression for GIF
     */
    private fun lzwCompress(indices: ByteArray, minCodeSize: Int): ByteArray {
        Trace.beginSection("LZW_COMPRESSION")
        try {
            val output = ByteArrayOutputStream()
            val lzw = LzwEncoder(minCodeSize)
            
            indices.forEach { index ->
                lzw.encode(index.toInt(), output)
            }
            lzw.finish(output)
            
            return output.toByteArray()
        } finally {
            Trace.endSection()
        }
    }
    
    private fun getPaletteSize(colorCount: Int): Int {
        // Return power of 2 minus 1 (e.g., 7 for 256 colors)
        return when {
            colorCount <= 2 -> 0
            colorCount <= 4 -> 1
            colorCount <= 8 -> 2
            colorCount <= 16 -> 3
            colorCount <= 32 -> 4
            colorCount <= 64 -> 5
            colorCount <= 128 -> 6
            else -> 7
        }
    }
    
    /**
     * Simple LZW encoder for GIF compression
     */
    private class LzwEncoder(private val minCodeSize: Int) {
        private val clearCode = 1 shl minCodeSize
        private val endCode = clearCode + 1
        private var nextCode = endCode + 1
        private val codeSize = minCodeSize + 1
        private val maxCode = (1 shl codeSize) - 1
        
        private val dictionary = mutableMapOf<String, Int>()
        private var currentSequence = ""
        private val bitBuffer = BitBuffer()
        
        init {
            // Initialize dictionary with single-byte codes
            for (i in 0 until clearCode) {
                dictionary[i.toChar().toString()] = i
            }
            
            // Start with clear code
            bitBuffer.writeBits(clearCode, codeSize)
        }
        
        fun encode(byte: Int, output: OutputStream) {
            val char = byte.toChar().toString()
            val newSequence = currentSequence + char
            
            if (dictionary.containsKey(newSequence)) {
                currentSequence = newSequence
            } else {
                // Output code for current sequence
                dictionary[currentSequence]?.let {
                    bitBuffer.writeBits(it, codeSize)
                }
                
                // Add new sequence to dictionary if space available
                if (nextCode <= maxCode) {
                    dictionary[newSequence] = nextCode++
                }
                
                currentSequence = char
            }
            
            // Flush buffer if needed
            bitBuffer.flushFullBytes(output)
        }
        
        fun finish(output: OutputStream) {
            // Output remaining sequence
            if (currentSequence.isNotEmpty()) {
                dictionary[currentSequence]?.let {
                    bitBuffer.writeBits(it, codeSize)
                }
            }
            
            // Output end code
            bitBuffer.writeBits(endCode, codeSize)
            
            // Flush remaining bits
            bitBuffer.flush(output)
        }
    }
    
    /**
     * Bit buffer for LZW encoding
     */
    private class BitBuffer {
        private var buffer = 0
        private var bitCount = 0
        
        fun writeBits(value: Int, bits: Int) {
            buffer = buffer or (value shl bitCount)
            bitCount += bits
        }
        
        fun flushFullBytes(output: OutputStream) {
            while (bitCount >= 8) {
                output.write(buffer and 0xFF)
                buffer = buffer shr 8
                bitCount -= 8
            }
        }
        
        fun flush(output: OutputStream) {
            if (bitCount > 0) {
                output.write(buffer and 0xFF)
            }
        }
    }
}

/**
 * Result of GIF export operation
 */
data class GifExportResult(
    val success: Boolean,
    val outputFile: File? = null,
    val paletteSize: Int = 0,
    val fileSize: Long = 0,
    val exportTimeMs: Long = 0,
    val quantizer: String = "",
    val ditheringUsed: Boolean = false,
    val error: String? = null
)