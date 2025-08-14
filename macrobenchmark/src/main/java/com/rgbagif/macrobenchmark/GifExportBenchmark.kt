package com.rgbagif.macrobenchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.TraceSectionMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.filters.SdkSuppress
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Macrobenchmark for M3 GIF export performance
 * Measures quantization, dithering, and GIF encoding performance
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
@SdkSuppress(minSdkVersion = 26)
class GifExportBenchmark {
    
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()
    
    companion object {
        private const val PACKAGE_NAME = "com.rgbagif"
        private const val ITERATIONS = 3
        private const val CAPTURE_TIMEOUT = 30000L
        private const val EXPORT_TIMEOUT = 60000L
    }
    
    @Test
    fun measureGifExportPerformance() {
        benchmarkRule.measureRepeated(
            packageName = PACKAGE_NAME,
            metrics = listOf(
                // M3 GIF export metrics
                TraceSectionMetric("GIF89a_EXPORT", TraceSectionMetric.Mode.First),
                TraceSectionMetric("MedianCutQuantizer", TraceSectionMetric.Mode.Average),
                TraceSectionMetric("OctreeQuantizer", TraceSectionMetric.Mode.Average),
                TraceSectionMetric("QUANTIZATION", TraceSectionMetric.Mode.Average),
                TraceSectionMetric("DITHERING", TraceSectionMetric.Mode.Average),
                TraceSectionMetric("LZW_COMPRESSION", TraceSectionMetric.Mode.Average),
                TraceSectionMetric("GIF_WRITE", TraceSectionMetric.Mode.Average),
                FrameTimingMetric()
            ),
            iterations = ITERATIONS,
            startupMode = StartupMode.WARM,
            compilationMode = CompilationMode.Partial()
        ) {
            startActivityAndWait()
            grantCameraPermission()
            
            // Capture frames first
            captureFramesForGif()
            
            // Navigate to export
            navigateToExport()
            
            // Start GIF export
            startGifExport()
            
            // Wait for export to complete
            waitForExportComplete()
        }
    }
    
    @Test
    fun measureQuantizerComparison() {
        // Compare different quantizers
        benchmarkRule.measureRepeated(
            packageName = PACKAGE_NAME,
            metrics = listOf(
                TraceSectionMetric("MedianCutQuantizer", TraceSectionMetric.Mode.Average),
                TraceSectionMetric("OctreeQuantizer", TraceSectionMetric.Mode.Average),
                TraceSectionMetric("QUANTIZATION", TraceSectionMetric.Mode.Average),
                TraceSectionMetric("COLOR_HISTOGRAM", TraceSectionMetric.Mode.Average),
                TraceSectionMetric("PALETTE_GENERATION", TraceSectionMetric.Mode.Average),
                TraceSectionMetric("FRAME_INDEXING", TraceSectionMetric.Mode.Average)
            ),
            iterations = ITERATIONS,
            startupMode = StartupMode.WARM,
            compilationMode = CompilationMode.Partial()
        ) {
            startActivityAndWait()
            grantCameraPermission()
            
            // Test MedianCut
            setQuantizer("MEDIAN_CUT")
            captureAndExportGif()
            
            // Test Octree
            setQuantizer("OCTREE")
            captureAndExportGif()
        }
    }
    
    @Test
    fun measureDitheringImpact() {
        // Measure performance impact of dithering
        benchmarkRule.measureRepeated(
            packageName = PACKAGE_NAME,
            metrics = listOf(
                TraceSectionMetric("DITHERING", TraceSectionMetric.Mode.Average),
                TraceSectionMetric("FLOYD_STEINBERG", TraceSectionMetric.Mode.Average),
                TraceSectionMetric("ERROR_DISTRIBUTION", TraceSectionMetric.Mode.Average),
                TraceSectionMetric("QUANTIZATION", TraceSectionMetric.Mode.Average)
            ),
            iterations = ITERATIONS,
            startupMode = StartupMode.WARM,
            compilationMode = CompilationMode.Partial()
        ) {
            startActivityAndWait()
            grantCameraPermission()
            
            // Test without dithering
            setDithering(false)
            captureAndExportGif()
            
            // Test with dithering
            setDithering(true)
            captureAndExportGif()
        }
    }
    
    @Test
    fun measureLzwCompressionPerformance() {
        // Measure LZW compression for GIF encoding
        benchmarkRule.measureRepeated(
            packageName = PACKAGE_NAME,
            metrics = listOf(
                TraceSectionMetric("LZW_COMPRESSION", TraceSectionMetric.Mode.Average),
                TraceSectionMetric("LZW_DICTIONARY_BUILD", TraceSectionMetric.Mode.Average),
                TraceSectionMetric("LZW_ENCODE", TraceSectionMetric.Mode.Average),
                TraceSectionMetric("GIF_WRITE", TraceSectionMetric.Mode.Average),
                TraceSectionMetric("BIT_BUFFER_FLUSH", TraceSectionMetric.Mode.Sum)
            ),
            iterations = ITERATIONS,
            startupMode = StartupMode.WARM,
            compilationMode = CompilationMode.Partial()
        ) {
            startActivityAndWait()
            grantCameraPermission()
            
            // Capture and export with focus on compression
            captureFramesForGif()
            navigateToExport()
            startGifExport()
            waitForExportComplete()
        }
    }
    
    @Test
    fun measureEndToEndWorkflow() {
        // Full workflow from capture to GIF export
        benchmarkRule.measureRepeated(
            packageName = PACKAGE_NAME,
            metrics = listOf(
                // M1 capture metrics
                TraceSectionMetric("M1_CAPTURE", TraceSectionMetric.Mode.First),
                TraceSectionMetric("M1_JNI_WRITE", TraceSectionMetric.Mode.Sum),
                TraceSectionMetric("CBOR_ENCODE", TraceSectionMetric.Mode.Average),
                TraceSectionMetric("FILE_WRITE", TraceSectionMetric.Mode.Average),
                // M3 export metrics
                TraceSectionMetric("GIF89a_EXPORT", TraceSectionMetric.Mode.First),
                TraceSectionMetric("QUANTIZATION", TraceSectionMetric.Mode.Average),
                TraceSectionMetric("LZW_COMPRESSION", TraceSectionMetric.Mode.Average),
                FrameTimingMetric()
            ),
            iterations = 2, // Fewer iterations for full workflow
            startupMode = StartupMode.WARM,
            compilationMode = CompilationMode.Partial()
        ) {
            startActivityAndWait()
            grantCameraPermission()
            
            // Full workflow
            device.wait(Until.hasObject(By.text("START")), 5000)
            device.findObject(By.text("START"))?.click()
            
            // Capture for 10 seconds
            Thread.sleep(10000)
            
            device.findObject(By.text("STOP"))?.click()
            
            // Export to GIF
            navigateToExport()
            startGifExport()
            waitForExportComplete()
        }
    }
    
    // Helper functions
    
    private fun MacrobenchmarkScope.grantCameraPermission() {
        val permissionDialog = device.wait(
            Until.hasObject(By.text("Allow")),
            1000
        )
        if (permissionDialog) {
            device.findObject(By.text("Allow"))?.click()
        }
    }
    
    private fun MacrobenchmarkScope.captureFramesForGif() {
        device.wait(Until.hasObject(By.text("START")), 5000)
        device.findObject(By.text("START"))?.click()
        Thread.sleep(5000) // Capture for 5 seconds
        device.findObject(By.text("STOP"))?.click()
        Thread.sleep(1000) // Wait for capture to finish
    }
    
    private fun MacrobenchmarkScope.navigateToExport() {
        // Navigate to export screen
        val exportButton = device.wait(
            Until.hasObject(By.text("EXPORT")),
            5000
        )
        if (exportButton) {
            device.findObject(By.text("EXPORT"))?.click()
        }
    }
    
    private fun MacrobenchmarkScope.startGifExport() {
        val gifButton = device.wait(
            Until.hasObject(By.text("Export as GIF")),
            5000
        )
        if (gifButton) {
            device.findObject(By.text("Export as GIF"))?.click()
        }
    }
    
    private fun MacrobenchmarkScope.waitForExportComplete() {
        device.wait(
            Until.hasObject(By.textContains("Export complete")),
            EXPORT_TIMEOUT
        )
    }
    
    private fun MacrobenchmarkScope.setQuantizer(type: String) {
        // Would navigate to settings and select quantizer
        // Implementation depends on UI
    }
    
    private fun MacrobenchmarkScope.setDithering(enabled: Boolean) {
        // Would navigate to settings and toggle dithering
        // Implementation depends on UI
    }
    
    private fun MacrobenchmarkScope.captureAndExportGif() {
        captureFramesForGif()
        navigateToExport()
        startGifExport()
        waitForExportComplete()
    }
}