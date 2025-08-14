package com.rgbagif.tools.cbor

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * CBOR frame schema for RGBA frame data.
 * This is the source of truth for frame storage and retrieval.
 */
@Serializable
data class RgbaFrame(
    val width: Int,
    val height: Int,
    val format: String = "RGBA_8888",
    val rgba: ByteArray,
    val timestampMs: Long,
    val meta: Map<String, String> = emptyMap()
) {
    /**
     * Frame dimensions for validation
     */
    val pixelCount: Int
        get() = width * height
    
    /**
     * Expected byte array size for RGBA
     */
    val expectedByteSize: Int
        get() = pixelCount * 4
    
    /**
     * Validate frame data consistency
     */
    fun validate(): Boolean {
        return rgba.size == expectedByteSize && 
               format == "RGBA_8888" &&
               width > 0 && 
               height > 0
    }
    
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        
        other as RgbaFrame
        
        if (width != other.width) return false
        if (height != other.height) return false
        if (format != other.format) return false
        if (!rgba.contentEquals(other.rgba)) return false
        if (timestampMs != other.timestampMs) return false
        if (meta != other.meta) return false
        
        return true
    }
    
    override fun hashCode(): Int {
        var result = width
        result = 31 * result + height
        result = 31 * result + format.hashCode()
        result = 31 * result + rgba.contentHashCode()
        result = 31 * result + timestampMs.hashCode()
        result = 31 * result + meta.hashCode()
        return result
    }
}

/**
 * CBOR frame metadata for browser display
 */
@Serializable
data class FrameMetadata(
    val index: Int,
    val sessionId: String,
    val capturedAt: Long,
    val deltaEMean: Float? = null,
    val deltaEMax: Float? = null,
    val paletteSize: Int? = null
)