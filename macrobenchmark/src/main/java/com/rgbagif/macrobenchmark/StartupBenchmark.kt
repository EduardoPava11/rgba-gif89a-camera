package com.rgbagif.macrobenchmark

import android.os.Build
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
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
 * Macrobenchmark for app startup and M1 milestone performance
 * Measures time to initial display, full display, and custom M1 sections
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
@SdkSuppress(minSdkVersion = 26)
class StartupBenchmark {
    
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()
    
    companion object {
        private const val PACKAGE_NAME = "com.rgbagif"
        private const val ITERATIONS = 5
        private const val STARTUP_TIMEOUT = 5000L
        private const val M1_TIMEOUT = 30000L
    }
    
    @Test
    fun measureColdStartup() {
        benchmarkRule.measureRepeated(
            packageName = PACKAGE_NAME,
            metrics = listOf(
                StartupTimingMetric(),
                FrameTimingMetric()
            ),
            iterations = ITERATIONS,
            startupMode = StartupMode.COLD,
            compilationMode = CompilationMode.Partial()
        ) {
            startActivityAndWait()
            
            // Wait for main content to be visible
            device.wait(Until.hasObject(By.text("RGBA→GIF89a Workflow")), STARTUP_TIMEOUT)
        }
    }
    
    @Test
    fun measureWarmStartup() {
        benchmarkRule.measureRepeated(
            packageName = PACKAGE_NAME,
            metrics = listOf(
                StartupTimingMetric(),
                FrameTimingMetric()
            ),
            iterations = ITERATIONS,
            startupMode = StartupMode.WARM,
            compilationMode = CompilationMode.Partial()
        ) {
            startActivityAndWait()
            
            // Verify app is responsive
            device.wait(Until.hasObject(By.text("START")), STARTUP_TIMEOUT)
        }
    }
    
    @Test
    fun measureHotStartup() {
        benchmarkRule.measureRepeated(
            packageName = PACKAGE_NAME,
            metrics = listOf(
                StartupTimingMetric(),
                FrameTimingMetric()
            ),
            iterations = ITERATIONS,
            startupMode = StartupMode.HOT,
            compilationMode = CompilationMode.Partial()
        ) {
            startActivityAndWait()
        }
    }
    
    @Test
    fun measureM1CapturePerformance() {
        benchmarkRule.measureRepeated(
            packageName = PACKAGE_NAME,
            metrics = listOf(
                // Custom trace sections for M1 phases
                TraceSectionMetric("M1_CAPTURE", TraceSectionMetric.Mode.First),
                TraceSectionMetric("M1_CBOR_WRITE", TraceSectionMetric.Mode.Sum),
                TraceSectionMetric("M1_JNI_WRITE", TraceSectionMetric.Mode.Sum),
                TraceSectionMetric("M1_UNIFFI_WRITE", TraceSectionMetric.Mode.Sum),
                // New detailed metrics
                TraceSectionMetric("CBOR_ENCODE", TraceSectionMetric.Mode.Average),
                TraceSectionMetric("FILE_WRITE", TraceSectionMetric.Mode.Average),
                TraceSectionMetric("BUFFER_POOL_ACQUIRE", TraceSectionMetric.Mode.Average),
                TraceSectionMetric("BUFFER_POOL_RELEASE", TraceSectionMetric.Mode.Average),
                FrameTimingMetric()
            ),
            iterations = 3, // Fewer iterations for longer test
            startupMode = StartupMode.WARM,
            compilationMode = CompilationMode.Partial()
        ) {
            startActivityAndWait()
            
            // Grant camera permission if needed
            grantCameraPermission()
            
            // Wait for UI to be ready
            device.wait(Until.hasObject(By.text("START")), STARTUP_TIMEOUT)
            
            // Start M1 capture
            val startButton = device.findObject(By.text("START"))
            if (startButton != null) {
                startButton.click()
                
                // Wait for some frames to be captured
                Thread.sleep(5000)
                
                // Stop capture
                val stopButton = device.findObject(By.text("STOP"))
                stopButton?.click()
                
                // Wait for processing to complete
                device.wait(
                    Until.hasObject(By.textContains("complete")),
                    M1_TIMEOUT
                )
            }
        }
    }
    
    @Test
    fun measureJniVsUniffiSwitching() {
        // Measure the overhead of switching between JNI and UniFFI paths
        benchmarkRule.measureRepeated(
            packageName = PACKAGE_NAME,
            metrics = listOf(
                TraceSectionMetric("M1_JNI_WRITE", TraceSectionMetric.Mode.Average),
                TraceSectionMetric("M1_UNIFFI_WRITE", TraceSectionMetric.Mode.Average),
                TraceSectionMetric("M1Fast.writeFrame", TraceSectionMetric.Mode.Average),
                // Additional detailed metrics
                TraceSectionMetric("DirectByteBuffer_Access", TraceSectionMetric.Mode.Average),
                TraceSectionMetric("CBOR_ENCODE", TraceSectionMetric.Mode.Average),
                TraceSectionMetric("FILE_WRITE", TraceSectionMetric.Mode.Average)
            ),
            iterations = 3,
            startupMode = StartupMode.WARM,
            compilationMode = CompilationMode.Partial()
        ) {
            startActivityAndWait()
            grantCameraPermission()
            
            // Test JNI path
            toggleFastPath(true)
            captureFrames(5)
            
            // Test UniFFI path  
            toggleFastPath(false)
            captureFrames(5)
        }
    }
    
    @Test
    fun measureMemoryDuringCapture() {
        // Track memory usage during frame capture
        benchmarkRule.measureRepeated(
            packageName = PACKAGE_NAME,
            metrics = listOf(
                // Memory metrics are captured via Perfetto
                TraceSectionMetric("M1_CAPTURE", TraceSectionMetric.Mode.First),
                FrameTimingMetric()
            ),
            iterations = 3,
            startupMode = StartupMode.WARM,
            compilationMode = CompilationMode.Partial()
        ) {
            startActivityAndWait()
            grantCameraPermission()
            
            // Capture frames and monitor memory
            device.wait(Until.hasObject(By.text("START")), STARTUP_TIMEOUT)
            device.findObject(By.text("START"))?.click()
            
            // Capture for 10 seconds
            Thread.sleep(10000)
            
            device.findObject(By.text("STOP"))?.click()
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
    
    private fun MacrobenchmarkScope.toggleFastPath(enable: Boolean) {
        // Would interact with settings UI or use test API
        // For now, this is a placeholder
    }
    
    private fun MacrobenchmarkScope.captureFrames(count: Int) {
        val startButton = device.findObject(By.text("START"))
        if (startButton != null) {
            startButton.click()
            Thread.sleep((count * 200).toLong()) // Approximate time for frames
            device.findObject(By.text("STOP"))?.click()
        }
    }
}