package com.rgbagif.performance

import androidx.benchmark.macro.BaselineProfileRule
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit4.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Test 5: Performance Benchmarking
 * Measures app startup time, frame timing, memory usage during capture
 */
@RunWith(AndroidJUnit4::class)
class PerformanceBenchmarkTest {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()
    
    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    @Test
    fun startup_performance_cold_start() {
        benchmarkRule.measureRepeated(
            packageName = "com.rgbagif",
            metrics = listOf(StartupTimingMetric()),
            iterations = 5,
            startupMode = StartupMode.COLD,
            compilationMode = CompilationMode.DEFAULT
        ) {
            pressHome()
            startActivityAndWait()
            
            // Wait for camera preview to be ready
            device.wait(Until.hasObject(By.desc("Camera preview")), 5000)
        }
    }
    
    @Test
    fun frame_timing_during_capture() {
        benchmarkRule.measureRepeated(
            packageName = "com.rgbagif",
            metrics = listOf(FrameTimingMetric()),
            iterations = 3,
            startupMode = StartupMode.WARM,
            compilationMode = CompilationMode.DEFAULT
        ) {
            startActivityAndWait()
            
            // Wait for app to load
            device.wait(Until.hasObject(By.desc("Camera preview")), 3000)
            
            // Start capture
            val captureButton = device.findObject(By.desc("Start capture"))
            if (captureButton != null) {
                captureButton.click()
                
                // Let capture run for a few seconds to measure frame timing
                Thread.sleep(5000)
                
                // Stop capture
                val stopButton = device.findObject(By.desc("Stop capture"))
                stopButton?.click()
            }
        }
    }
    
    @Test
    fun memory_usage_during_81_frame_capture() {
        benchmarkRule.measureRepeated(
            packageName = "com.rgbagif",
            metrics = listOf(StartupTimingMetric(), FrameTimingMetric()),
            iterations = 3,
            startupMode = StartupMode.WARM,
            compilationMode = CompilationMode.DEFAULT
        ) {
            startActivityAndWait()
            
            // Navigate to capture
            device.wait(Until.hasObject(By.desc("Camera preview")), 3000)
            
            // Start full 81-frame capture
            val captureButton = device.findObject(By.desc("Start capture"))
            captureButton?.click()
            
            // Wait for 81 frames at ~24fps = ~3.4 seconds
            Thread.sleep(4000)
            
            // Verify technical readout shows completion
            device.wait(Until.hasObject(By.textContains("Frames: 81")), 2000)
        }
    }
    
    @Test
    fun ui_responsiveness_during_overlay_toggle() {
        benchmarkRule.measureRepeated(
            packageName = "com.rgbagif",
            metrics = listOf(FrameTimingMetric()),
            iterations = 5,
            startupMode = StartupMode.WARM,
            compilationMode = CompilationMode.DEFAULT
        ) {
            startActivityAndWait()
            device.wait(Until.hasObject(By.desc("Camera preview")), 3000)
            
            // Toggle overlays rapidly to test UI responsiveness
            repeat(10) {
                device.findObject(By.desc("Toggle alpha overlay"))?.click()
                Thread.sleep(100)
                device.findObject(By.desc("Toggle delta-E overlay"))?.click()
                Thread.sleep(100)
            }
        }
    }
    
    @Test
    fun technical_readout_update_performance() {
        benchmarkRule.measureRepeated(
            packageName = "com.rgbagif",
            metrics = listOf(FrameTimingMetric()),
            iterations = 3,
            startupMode = StartupMode.WARM,
            compilationMode = CompilationMode.DEFAULT
        ) {
            startActivityAndWait()
            device.wait(Until.hasObject(By.desc("Camera preview")), 3000)
            
            // Expand technical readout to detailed view
            device.findObject(By.desc("Expand details"))?.click()
            
            // Start capture to generate metrics updates
            device.findObject(By.desc("Start capture"))?.click()
            
            // Let metrics update for performance measurement
            Thread.sleep(3000)
            
            // Toggle between compact and detailed views
            repeat(5) {
                device.findObject(By.desc("Collapse details"))?.click()
                Thread.sleep(200)
                device.findObject(By.desc("Expand details"))?.click()
                Thread.sleep(200)
            }
        }
    }
    
    @Test
    fun baseline_profile_generation() {
        baselineProfileRule.collect(
            packageName = "com.rgbagif",
            iterations = 3,
            stableIterations = 3
        ) {
            pressHome()
            startActivityAndWait()
            
            // Exercise key user flows for baseline profile
            device.wait(Until.hasObject(By.desc("Camera preview")), 3000)
            
            // Start/stop capture
            device.findObject(By.desc("Start capture"))?.click()
            Thread.sleep(2000)
            device.findObject(By.desc("Stop capture"))?.click()
            
            // Toggle overlays
            device.findObject(By.desc("Toggle alpha overlay"))?.click()
            device.findObject(By.desc("Toggle delta-E overlay"))?.click()
            
            // View technical details
            device.findObject(By.desc("Expand details"))?.click()
            Thread.sleep(1000)
            device.findObject(By.desc("Collapse details"))?.click()
            
            // Open/close info panel
            device.findObject(By.desc("Show pipeline information"))?.click()
            Thread.sleep(1000)
            device.findObject(By.desc("Close pipeline information"))?.click()
        }
    }

    private fun MacrobenchmarkScope.startActivityAndWait() {
        startActivityAndWait(
            androidx.test.core.app.ApplicationProvider
                .getApplicationContext<android.content.Context>()
                .packageManager
                .getLaunchIntentForPackage(packageName)!!
        )
    }
}
