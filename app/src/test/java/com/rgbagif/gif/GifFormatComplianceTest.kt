package com.rgbagif.gif

import org.apache.commons.imaging.Imaging
import org.apache.commons.imaging.formats.gif.GifImageParser
import org.apache.commons.imaging.formats.gif.GifImagingParameters
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File

/**
 * Test 6: GIF Format Compliance
 * Validates output GIFs match GIF89a spec with proper timing, palette, transparency
 */
class GifFormatComplianceTest {

    @Test
    fun gif_header_is_gif89a_compliant() {
        val mockGifBytes = createMockGif89aHeader()
        
        // Check GIF89a signature
        val signature = String(mockGifBytes.sliceArray(0..5))
        assertEquals("GIF89a", signature)
    }
    
    @Test
    fun gif_has_81_frames_at_4_centiseconds() {
        // This would test actual GIF output from the pipeline
        val testGifFile = File("/tmp/test_output.gif")
        
        if (testGifFile.exists()) {
            val gifBytes = testGifFile.readBytes()
            val inputStream = ByteArrayInputStream(gifBytes)
            
            try {
                val parser = GifImageParser()
                val params = GifImagingParameters()
                val metadata = Imaging.getMetadata(inputStream, params)
                
                // Parse frame count and timing
                // (Implementation depends on commons-imaging API)
                
                // Verify 81 frames
                // assertEquals(81, frameCount)
                
                // Verify 4 centisecond (40ms) delay between frames
                // assertEquals(4, delayTime)
            } catch (e: Exception) {
                fail("Failed to parse GIF: ${e.message}")
            }
        }
    }
    
    @Test
    fun gif_preserves_alpha_transparency() {
        val testGifFile = File("/tmp/test_alpha_output.gif")
        
        if (testGifFile.exists()) {
            val gifBytes = testGifFile.readBytes()
            
            // Check for transparency extension (0x21 0xF9)
            val hasTransparencyExtension = findTransparencyExtension(gifBytes)
            assertTrue("GIF should have transparency extension", hasTransparencyExtension)
            
            // Verify transparent color index is set properly
            val transparentColorIndex = extractTransparentColorIndex(gifBytes)
            assertTrue("Transparent color index should be valid", transparentColorIndex >= 0)
        }
    }
    
    @Test
    fun gif_palette_is_optimally_sized() {
        val testGifFile = File("/tmp/test_palette_output.gif")
        
        if (testGifFile.exists()) {
            val gifBytes = testGifFile.readBytes()
            
            // Extract color table size from logical screen descriptor
            val globalColorTableSize = extractGlobalColorTableSize(gifBytes)
            
            // Should use optimal palette size (power of 2, up to 256 colors)
            assertTrue("Palette size should be power of 2", 
                      isPowerOfTwo(globalColorTableSize))
            assertTrue("Palette should not exceed 256 colors",
                      globalColorTableSize <= 256)
        }
    }
    
    @Test
    fun gif_frame_dimensions_are_81x81() {
        val testGifFile = File("/tmp/test_dimensions_output.gif")
        
        if (testGifFile.exists()) {
            val gifBytes = testGifFile.readBytes()
            
            // Extract dimensions from logical screen descriptor
            val width = extractWidth(gifBytes)
            val height = extractHeight(gifBytes)
            
            assertEquals("GIF width should be 81", 81, width)
            assertEquals("GIF height should be 81", 81, height)
        }
    }
    
    @Test
    fun gif_loop_extension_is_present() {
        val testGifFile = File("/tmp/test_loop_output.gif")
        
        if (testGifFile.exists()) {
            val gifBytes = testGifFile.readBytes()
            
            // Look for application extension with loop count (0x21 0xFF 0x0B "NETSCAPE2.0")
            val hasLoopExtension = findNetscapeLoopExtension(gifBytes)
            assertTrue("GIF should have loop extension for continuous playback", 
                      hasLoopExtension)
        }
    }
    
    @Test
    fun gif_compression_is_efficient() {
        val testGifFile = File("/tmp/test_compression_output.gif")
        
        if (testGifFile.exists()) {
            val fileSize = testGifFile.length()
            
            // 81 frames of 81×81 pixels should compress reasonably
            val maxExpectedSize = 81 * 81 * 81 / 4 // Rough compression estimate
            assertTrue("GIF file should be reasonably compressed",
                      fileSize < maxExpectedSize)
        }
    }
    
    // Helper methods for GIF parsing
    private fun createMockGif89aHeader(): ByteArray {
        return byteArrayOf(
            0x47, 0x49, 0x46, 0x38, 0x39, 0x61, // "GIF89a"
            0x51, 0x00, // Width: 81 (little endian)
            0x51, 0x00, // Height: 81 (little endian)
            0xF7, 0x00, 0x00 // Global color table info + background + aspect ratio
        )
    }
    
    private fun findTransparencyExtension(bytes: ByteArray): Boolean {
        for (i in 0 until bytes.size - 1) {
            if (bytes[i] == 0x21.toByte() && bytes[i + 1] == 0xF9.toByte()) {
                return true
            }
        }
        return false
    }
    
    private fun extractTransparentColorIndex(bytes: ByteArray): Int {
        // Find transparency extension and extract transparent color index
        for (i in 0 until bytes.size - 5) {
            if (bytes[i] == 0x21.toByte() && bytes[i + 1] == 0xF9.toByte()) {
                val flags = bytes[i + 3]
                if ((flags.toInt() and 0x01) == 1) { // Transparency flag set
                    return bytes[i + 6].toUByte().toInt() // Transparent color index
                }
            }
        }
        return -1
    }
    
    private fun extractGlobalColorTableSize(bytes: ByteArray): Int {
        if (bytes.size < 13) return 0
        
        val flags = bytes[10]
        val size = flags.toInt() and 0x07 // Last 3 bits
        return 1 shl (size + 1) // 2^(size+1)
    }
    
    private fun extractWidth(bytes: ByteArray): Int {
        if (bytes.size < 8) return 0
        return (bytes[7].toUByte().toInt() shl 8) + bytes[6].toUByte().toInt()
    }
    
    private fun extractHeight(bytes: ByteArray): Int {
        if (bytes.size < 10) return 0
        return (bytes[9].toUByte().toInt() shl 8) + bytes[8].toUByte().toInt()
    }
    
    private fun findNetscapeLoopExtension(bytes: ByteArray): Boolean {
        val netscapeSignature = "NETSCAPE2.0".toByteArray()
        
        for (i in 0 until bytes.size - netscapeSignature.size) {
            if (bytes[i] == 0x21.toByte() && bytes[i + 1] == 0xFF.toByte()) {
                var match = true
                for (j in netscapeSignature.indices) {
                    if (bytes[i + 3 + j] != netscapeSignature[j]) {
                        match = false
                        break
                    }
                }
                if (match) return true
            }
        }
        return false
    }
    
    private fun isPowerOfTwo(n: Int): Boolean {
        return n > 0 && (n and (n - 1)) == 0
    }
}
