package com.rgbagif.utils

import android.graphics.Bitmap
import com.rgbagif.tools.cbor.CborTools
import com.rgbagif.tools.cbor.RgbaFrame
import com.rgbagif.tools.png.PngWriter
import java.io.File

/**
 * Bridge class to maintain compatibility with existing FrameStorage
 * while using the new CBOR tools architecture.
 */
data class CborFrame(
    val v: Int = 1,  // Version
    val ts: Long,    // Timestamp
    val w: Int,      // Width
    val h: Int,      // Height
    val fmt: String = "RGBA_8888",  // Format
    val stride: Int, // Row stride in bytes
    val premul: Boolean = false,  // Premultiplied alpha
    val colorspace: String = "sRGB",  // Color space
    val rgba: ByteArray  // RGBA data
) {
    companion object {
        /**
         * Create CborFrame from camera image data
         */
        @JvmStatic
        fun fromCameraImage(
            rgbaBuffer: ByteArray,
            width: Int,
            height: Int,
            rowStride: Int,
            timestampMs: Long
        ): CborFrame {
            // Handle stride if different from width * 4
            val rgba = if (rowStride == width * 4) {
                rgbaBuffer
            } else {
                // Remove padding from each row
                val cleanRgba = ByteArray(width * height * 4)
                for (row in 0 until height) {
                    System.arraycopy(
                        rgbaBuffer, row * rowStride,
                        cleanRgba, row * width * 4,
                        width * 4
                    )
                }
                cleanRgba
            }
            
            return CborFrame(
                v = 1,
                ts = timestampMs,
                w = width,
                h = height,
                fmt = "RGBA_8888",
                stride = width * 4,
                premul = false,
                colorspace = "sRGB",
                rgba = rgba
            )
        }
        
        /**
         * Encode CborFrame to CBOR bytes
         */
        @JvmStatic
        fun toCbor(frame: CborFrame): ByteArray {
            val rgbaFrame = RgbaFrame(
                width = frame.w,
                height = frame.h,
                format = frame.fmt,
                rgba = frame.rgba,
                timestampMs = frame.ts,
                meta = mapOf(
                    "version" to frame.v.toString(),
                    "stride" to frame.stride.toString(),
                    "premul" to frame.premul.toString(),
                    "colorspace" to frame.colorspace
                )
            )
            return CborTools.encodeFrame(rgbaFrame)
        }
        
        /**
         * Decode CborFrame from CBOR bytes
         */
        @JvmStatic
        fun fromCbor(cborBytes: ByteArray): CborFrame {
            val rgbaFrame = CborTools.decodeFrame(cborBytes)
            return CborFrame(
                v = rgbaFrame.meta["version"]?.toIntOrNull() ?: 1,
                ts = rgbaFrame.timestampMs,
                w = rgbaFrame.width,
                h = rgbaFrame.height,
                fmt = rgbaFrame.format,
                stride = rgbaFrame.meta["stride"]?.toIntOrNull() ?: (rgbaFrame.width * 4),
                premul = rgbaFrame.meta["premul"]?.toBoolean() ?: false,
                colorspace = rgbaFrame.meta["colorspace"] ?: "sRGB",
                rgba = rgbaFrame.rgba
            )
        }
    }
    
    /**
     * Export this frame to PNG file
     */
    fun exportToPng(file: File): Boolean {
        return PngWriter.rgbaToPng(rgba, w, h, file)
    }
    
    /**
     * Convert to Bitmap
     */
    fun toBitmap(): Bitmap {
        val pixels = IntArray(w * h)
        var srcIndex = 0
        
        for (i in pixels.indices) {
            val r = rgba[srcIndex++].toInt() and 0xFF
            val g = rgba[srcIndex++].toInt() and 0xFF
            val b = rgba[srcIndex++].toInt() and 0xFF
            val a = rgba[srcIndex++].toInt() and 0xFF
            
            pixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
        
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        return bitmap
    }
    
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        
        other as CborFrame
        
        if (v != other.v) return false
        if (ts != other.ts) return false
        if (w != other.w) return false
        if (h != other.h) return false
        if (fmt != other.fmt) return false
        if (stride != other.stride) return false
        if (premul != other.premul) return false
        if (colorspace != other.colorspace) return false
        if (!rgba.contentEquals(other.rgba)) return false
        
        return true
    }
    
    override fun hashCode(): Int {
        var result = v
        result = 31 * result + ts.hashCode()
        result = 31 * result + w
        result = 31 * result + h
        result = 31 * result + fmt.hashCode()
        result = 31 * result + stride
        result = 31 * result + premul.hashCode()
        result = 31 * result + colorspace.hashCode()
        result = 31 * result + rgba.contentHashCode()
        return result
    }
}