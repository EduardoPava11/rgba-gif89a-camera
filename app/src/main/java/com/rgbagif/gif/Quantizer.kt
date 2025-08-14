package com.rgbagif.gif

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Trace
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Interface for color quantization algorithms
 */
interface Quantizer {
    /**
     * Quantize multiple frames to a global palette
     * @param frames List of RGBA frames to quantize
     * @param maxColors Maximum colors in palette (typically 256 for GIF)
     * @param dithering Apply Floyd-Steinberg dithering
     * @return Quantization result with palette and indexed frames
     */
    fun quantize(
        frames: List<RgbaFrame>,
        maxColors: Int = 256,
        dithering: Boolean = false
    ): QuantizationResult
    
    /**
     * Get the name of this quantizer for logging/benchmarking
     */
    val name: String
}

/**
 * RGBA frame data
 */
data class RgbaFrame(
    val width: Int,
    val height: Int,
    val pixels: IntArray // ARGB format as used by Android Bitmap
) {
    companion object {
        fun fromBitmap(bitmap: Bitmap): RgbaFrame {
            val width = bitmap.width
            val height = bitmap.height
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            return RgbaFrame(width, height, pixels)
        }
    }
}

/**
 * Result of quantization
 */
data class QuantizationResult(
    val palette: IntArray, // Global palette (up to 256 colors)
    val indexedFrames: List<IndexedFrame>, // Frames with palette indices
    val quantizerName: String,
    val quantizationTimeMs: Long,
    val ditheringApplied: Boolean
)

/**
 * Frame represented as palette indices
 */
data class IndexedFrame(
    val width: Int,
    val height: Int,
    val indices: ByteArray // Palette indices (0-255)
)

/**
 * Median-cut quantizer - fast baseline implementation
 * Recursively splits color space by median along largest dimension
 */
class MedianCutQuantizer : Quantizer {
    override val name = "MedianCut"
    
    override fun quantize(
        frames: List<RgbaFrame>,
        maxColors: Int,
        dithering: Boolean
    ): QuantizationResult {
        Trace.beginSection("MedianCutQuantizer")
        val startTime = System.currentTimeMillis()
        
        try {
            // Collect all unique colors from all frames
            Trace.beginSection("COLOR_HISTOGRAM")
            val colorHistogram = mutableMapOf<Int, Int>()
            frames.forEach { frame ->
                frame.pixels.forEach { pixel ->
                    // Ignore fully transparent pixels
                    if (Color.alpha(pixel) > 0) {
                        colorHistogram[pixel] = (colorHistogram[pixel] ?: 0) + 1
                    }
                }
            }
            Trace.endSection()
            
            // Build initial color box
            val colorBox = ColorBox(colorHistogram.keys.toList())
            
            // Recursively split boxes until we have maxColors
            val boxes = mutableListOf(colorBox)
            while (boxes.size < maxColors && boxes.any { it.canSplit() }) {
                // Find box with largest volume to split
                val boxToSplit = boxes.filter { it.canSplit() }
                    .maxByOrNull { it.volume() } ?: break
                
                boxes.remove(boxToSplit)
                val (box1, box2) = boxToSplit.split()
                boxes.add(box1)
                boxes.add(box2)
            }
            
            // Extract palette from boxes
            Trace.beginSection("PALETTE_GENERATION")
            val palette = boxes.map { it.averageColor() }.toIntArray()
            Trace.endSection()
            
            // Build color lookup for fast indexing
            val colorToIndex = buildColorLookup(palette)
            
            // Index all frames
            Trace.beginSection("FRAME_INDEXING")
            val indexedFrames = frames.map { frame ->
                if (dithering) {
                    Trace.beginSection("DITHERING")
                    val result = indexFrameWithDithering(frame, palette, colorToIndex)
                    Trace.endSection()
                    result
                } else {
                    indexFrameNoDithering(frame, palette, colorToIndex)
                }
            }
            Trace.endSection()
            
            val quantizationTime = System.currentTimeMillis() - startTime
            
            return QuantizationResult(
                palette = palette,
                indexedFrames = indexedFrames,
                quantizerName = name,
                quantizationTimeMs = quantizationTime,
                ditheringApplied = dithering
            )
        } finally {
            Trace.endSection()
        }
    }
    
    private fun indexFrameNoDithering(
        frame: RgbaFrame,
        palette: IntArray,
        colorToIndex: Map<Int, Byte>
    ): IndexedFrame {
        val indices = ByteArray(frame.pixels.size)
        
        frame.pixels.forEachIndexed { i, pixel ->
            indices[i] = if (Color.alpha(pixel) == 0) {
                0 // Transparent pixels map to index 0
            } else {
                // Find nearest color in palette
                colorToIndex[pixel] ?: findNearestColorIndex(pixel, palette)
            }
        }
        
        return IndexedFrame(frame.width, frame.height, indices)
    }
    
    private fun indexFrameWithDithering(
        frame: RgbaFrame,
        palette: IntArray,
        colorToIndex: Map<Int, Byte>
    ): IndexedFrame {
        val width = frame.width
        val height = frame.height
        val indices = ByteArray(frame.pixels.size)
        
        // Copy pixels for dithering (we'll modify them)
        val pixels = frame.pixels.copyOf()
        
        for (y in 0 until height) {
            for (x in 0 until width) {
                val i = y * width + x
                val oldPixel = pixels[i]
                
                if (Color.alpha(oldPixel) == 0) {
                    indices[i] = 0
                    continue
                }
                
                // Find nearest palette color
                val nearestIndex = colorToIndex[oldPixel] 
                    ?: findNearestColorIndex(oldPixel, palette)
                indices[i] = nearestIndex
                
                val newPixel = palette[nearestIndex.toInt()]
                
                // Calculate error
                val errorR = Color.red(oldPixel) - Color.red(newPixel)
                val errorG = Color.green(oldPixel) - Color.green(newPixel)
                val errorB = Color.blue(oldPixel) - Color.blue(newPixel)
                
                // Floyd-Steinberg dithering: distribute error to neighbors
                Trace.beginSection("FLOYD_STEINBERG")
                // Right: 7/16
                if (x < width - 1) {
                    distributeError(pixels, i + 1, errorR, errorG, errorB, 7/16f)
                }
                // Bottom-left: 3/16
                if (y < height - 1 && x > 0) {
                    distributeError(pixels, i + width - 1, errorR, errorG, errorB, 3/16f)
                }
                // Bottom: 5/16
                if (y < height - 1) {
                    distributeError(pixels, i + width, errorR, errorG, errorB, 5/16f)
                }
                // Bottom-right: 1/16
                if (y < height - 1 && x < width - 1) {
                    distributeError(pixels, i + width + 1, errorR, errorG, errorB, 1/16f)
                }
                Trace.endSection()
            }
        }
        
        return IndexedFrame(width, height, indices)
    }
    
    private fun distributeError(
        pixels: IntArray,
        index: Int,
        errorR: Int,
        errorG: Int,
        errorB: Int,
        factor: Float
    ) {
        if (index >= pixels.size) return
        
        val pixel = pixels[index]
        if (Color.alpha(pixel) == 0) return
        
        val r = (Color.red(pixel) + errorR * factor).toInt().coerceIn(0, 255)
        val g = (Color.green(pixel) + errorG * factor).toInt().coerceIn(0, 255)
        val b = (Color.blue(pixel) + errorB * factor).toInt().coerceIn(0, 255)
        
        pixels[index] = Color.argb(Color.alpha(pixel), r, g, b)
    }
    
    private fun findNearestColorIndex(color: Int, palette: IntArray): Byte {
        var minDistance = Int.MAX_VALUE
        var nearestIndex = 0
        
        palette.forEachIndexed { index, paletteColor ->
            val distance = colorDistance(color, paletteColor)
            if (distance < minDistance) {
                minDistance = distance
                nearestIndex = index
            }
        }
        
        return nearestIndex.toByte()
    }
    
    private fun colorDistance(c1: Int, c2: Int): Int {
        val dr = Color.red(c1) - Color.red(c2)
        val dg = Color.green(c1) - Color.green(c2)
        val db = Color.blue(c1) - Color.blue(c2)
        return dr * dr + dg * dg + db * db
    }
    
    private fun buildColorLookup(palette: IntArray): Map<Int, Byte> {
        // Pre-compute for common colors to speed up indexing
        return mutableMapOf()
    }
    
    /**
     * Color box for median-cut algorithm
     */
    private class ColorBox(private val colors: List<Int>) {
        private val rMin: Int
        private val rMax: Int
        private val gMin: Int
        private val gMax: Int
        private val bMin: Int
        private val bMax: Int
        
        init {
            var minR = 255
            var maxR = 0
            var minG = 255
            var maxG = 0
            var minB = 255
            var maxB = 0
            
            colors.forEach { color ->
                val r = Color.red(color)
                val g = Color.green(color)
                val b = Color.blue(color)
                
                minR = minOf(minR, r)
                maxR = maxOf(maxR, r)
                minG = minOf(minG, g)
                maxG = maxOf(maxG, g)
                minB = minOf(minB, b)
                maxB = maxOf(maxB, b)
            }
            
            rMin = minR
            rMax = maxR
            gMin = minG
            gMax = maxG
            bMin = minB
            bMax = maxB
        }
        
        fun canSplit() = colors.size > 1
        
        fun volume() = (rMax - rMin) * (gMax - gMin) * (bMax - bMin)
        
        fun split(): Pair<ColorBox, ColorBox> {
            // Find dimension with largest range
            val rRange = rMax - rMin
            val gRange = gMax - gMin
            val bRange = bMax - bMin
            
            val sortedColors = when {
                rRange >= gRange && rRange >= bRange -> {
                    colors.sortedBy { Color.red(it) }
                }
                gRange >= bRange -> {
                    colors.sortedBy { Color.green(it) }
                }
                else -> {
                    colors.sortedBy { Color.blue(it) }
                }
            }
            
            val median = sortedColors.size / 2
            return Pair(
                ColorBox(sortedColors.subList(0, median)),
                ColorBox(sortedColors.subList(median, sortedColors.size))
            )
        }
        
        fun averageColor(): Int {
            var totalR = 0L
            var totalG = 0L
            var totalB = 0L
            
            colors.forEach { color ->
                totalR += Color.red(color)
                totalG += Color.green(color)
                totalB += Color.blue(color)
            }
            
            val count = colors.size
            return Color.rgb(
                (totalR / count).toInt(),
                (totalG / count).toInt(),
                (totalB / count).toInt()
            )
        }
    }
}