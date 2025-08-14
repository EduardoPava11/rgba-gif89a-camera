package com.rgbagif.gif

import android.graphics.Color
import android.os.Trace
import java.util.PriorityQueue

/**
 * Octree quantizer - builds a tree of color space subdivisions
 * Generally produces better quality than median-cut but slightly slower
 */
class OctreeQuantizer : Quantizer {
    override val name = "Octree"
    
    private val maxDepth = 8 // Maximum tree depth (8 bits per channel)
    
    override fun quantize(
        frames: List<RgbaFrame>,
        maxColors: Int,
        dithering: Boolean
    ): QuantizationResult {
        Trace.beginSection("OctreeQuantizer")
        val startTime = System.currentTimeMillis()
        
        try {
            // Build octree from all frames
            val octree = Octree()
            
            // Add all colors to octree
            frames.forEach { frame ->
                frame.pixels.forEach { pixel ->
                    if (Color.alpha(pixel) > 0) {
                        octree.addColor(pixel)
                    }
                }
            }
            
            // Reduce octree to maxColors
            octree.reduceTo(maxColors)
            
            // Extract palette
            val palette = octree.getPalette()
            
            // Index frames
            val indexedFrames = frames.map { frame ->
                if (dithering) {
                    indexFrameWithDithering(frame, octree)
                } else {
                    indexFrameNoDithering(frame, octree)
                }
            }
            
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
    
    private fun indexFrameNoDithering(frame: RgbaFrame, octree: Octree): IndexedFrame {
        val indices = ByteArray(frame.pixels.size)
        
        frame.pixels.forEachIndexed { i, pixel ->
            indices[i] = if (Color.alpha(pixel) == 0) {
                0
            } else {
                octree.getColorIndex(pixel)
            }
        }
        
        return IndexedFrame(frame.width, frame.height, indices)
    }
    
    private fun indexFrameWithDithering(frame: RgbaFrame, octree: Octree): IndexedFrame {
        val width = frame.width
        val height = frame.height
        val indices = ByteArray(frame.pixels.size)
        val pixels = frame.pixels.copyOf()
        
        for (y in 0 until height) {
            for (x in 0 until width) {
                val i = y * width + x
                val oldPixel = pixels[i]
                
                if (Color.alpha(oldPixel) == 0) {
                    indices[i] = 0
                    continue
                }
                
                val nearestIndex = octree.getColorIndex(oldPixel)
                indices[i] = nearestIndex
                
                val newPixel = octree.getColorAt(nearestIndex)
                
                // Calculate and distribute error (Floyd-Steinberg)
                val errorR = Color.red(oldPixel) - Color.red(newPixel)
                val errorG = Color.green(oldPixel) - Color.green(newPixel)
                val errorB = Color.blue(oldPixel) - Color.blue(newPixel)
                
                // Distribute to neighbors
                if (x < width - 1) {
                    distributeError(pixels, i + 1, errorR, errorG, errorB, 7/16f)
                }
                if (y < height - 1) {
                    if (x > 0) distributeError(pixels, i + width - 1, errorR, errorG, errorB, 3/16f)
                    distributeError(pixels, i + width, errorR, errorG, errorB, 5/16f)
                    if (x < width - 1) distributeError(pixels, i + width + 1, errorR, errorG, errorB, 1/16f)
                }
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
    
    /**
     * Octree data structure for color quantization
     */
    private inner class Octree {
        private val root = OctreeNode(0)
        private val levels = Array(maxDepth) { mutableListOf<OctreeNode>() }
        private var leafCount = 0
        
        fun addColor(color: Int) {
            root.addColor(color, 0)
        }
        
        fun reduceTo(maxColors: Int) {
            while (leafCount > maxColors) {
                // Find deepest level with reducible nodes
                for (level in (maxDepth - 1) downTo 0) {
                    if (levels[level].isNotEmpty()) {
                        // Reduce node with smallest pixel count
                        val nodeToReduce = levels[level].firstOrNull() ?: continue
                        nodeToReduce.reduce()
                        levels[level].remove(nodeToReduce)
                        break
                    }
                }
            }
        }
        
        fun getPalette(): IntArray {
            val colors = mutableListOf<Int>()
            root.collectPaletteColors(colors)
            return colors.toIntArray()
        }
        
        fun getColorIndex(color: Int): Byte {
            return root.getColorIndex(color, 0).toByte()
        }
        
        fun getColorAt(index: Byte): Int {
            val colors = getPalette()
            return if (index.toInt() < colors.size) {
                colors[index.toInt()]
            } else {
                0
            }
        }
        
        private inner class OctreeNode(private val level: Int) {
            private val children = arrayOfNulls<OctreeNode>(8)
            private var isLeaf = false
            private var pixelCount = 0
            private var red = 0L
            private var green = 0L
            private var blue = 0L
            private var paletteIndex = -1
            
            fun addColor(color: Int, depth: Int) {
                if (depth >= maxDepth || isLeaf) {
                    // Add color to this node
                    red += Color.red(color)
                    green += Color.green(color)
                    blue += Color.blue(color)
                    pixelCount++
                    
                    if (!isLeaf) {
                        isLeaf = true
                        leafCount++
                    }
                } else {
                    // Add to appropriate child
                    val index = getChildIndex(color, depth)
                    if (children[index] == null) {
                        children[index] = OctreeNode(level + 1)
                        if (level < maxDepth - 1) {
                            levels[level].add(children[index]!!)
                        }
                    }
                    children[index]!!.addColor(color, depth + 1)
                }
            }
            
            fun reduce() {
                // Merge children into this node
                red = 0
                green = 0
                blue = 0
                pixelCount = 0
                
                for (child in children) {
                    child?.let {
                        red += it.red
                        green += it.green
                        blue += it.blue
                        pixelCount += it.pixelCount
                        if (it.isLeaf) leafCount--
                    }
                }
                
                isLeaf = true
                leafCount++
                children.fill(null)
            }
            
            fun collectPaletteColors(colors: MutableList<Int>) {
                if (isLeaf) {
                    val r = (red / pixelCount).toInt()
                    val g = (green / pixelCount).toInt()
                    val b = (blue / pixelCount).toInt()
                    paletteIndex = colors.size
                    colors.add(Color.rgb(r, g, b))
                } else {
                    for (child in children) {
                        child?.collectPaletteColors(colors)
                    }
                }
            }
            
            fun getColorIndex(color: Int, depth: Int): Int {
                if (isLeaf) {
                    return paletteIndex
                }
                
                val index = getChildIndex(color, depth)
                return children[index]?.getColorIndex(color, depth + 1) ?: 0
            }
            
            private fun getChildIndex(color: Int, depth: Int): Int {
                val shift = 7 - depth
                val mask = 1 shl shift
                
                var index = 0
                if (Color.red(color) and mask != 0) index = index or 4
                if (Color.green(color) and mask != 0) index = index or 2
                if (Color.blue(color) and mask != 0) index = index or 1
                
                return index
            }
        }
    }
}