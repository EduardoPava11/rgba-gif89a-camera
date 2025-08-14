package com.rgbagif.e2e

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit4.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import kotlinx.coroutines.delay
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import com.rgbagif.MainActivity
import java.io.File

/**
 * Test 8: End-to-End User Flows
 * Tests complete capture → processing → GIF creation → file output workflow
 */
@RunWith(AndroidJUnit4::class)
class EndToEndUserFlowTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()
    
    private lateinit var device: UiDevice
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    
    @Before
    fun setup() {
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        
        // Grant camera permission if needed
        grantCameraPermission()
    }
    
    @Test
    fun complete_capture_to_gif_workflow() {
        // 1. App launches and shows camera preview
        composeTestRule.onNodeWithContentDescription("Camera preview")
            .assertIsDisplayed()
        
        // 2. Technical readout shows initial state
        composeTestRule.onNodeWithText("Frames: 0")
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("FPS: 0.0")
            .assertIsDisplayed()
        
        // 3. Start capture
        composeTestRule.onNodeWithContentDescription("Start capture")
            .assertIsDisplayed()
            .performClick()
        
        // 4. Wait for capture to complete (81 frames at ~24fps ≈ 3.4 seconds)
        composeTestRule.waitUntil(timeoutMillis = 5000) {
            try {
                composeTestRule.onNodeWithText("Frames: 81").fetchSemanticsNode()
                true
            } catch (e: AssertionError) {
                false
            }
        }
        
        // 5. Verify capture completion
        composeTestRule.onNodeWithText("Frames: 81")
            .assertIsDisplayed()
        
        // 6. Verify GIF was created
        val outputDir = File(context.getExternalFilesDir(null), "gifs")
        composeTestRule.waitUntil(timeoutMillis = 3000) {
            outputDir.exists() && outputDir.listFiles()?.isNotEmpty() == true
        }
        
        val gifFiles = outputDir.listFiles { _, name -> name.endsWith(".gif") }
        assertTrue("GIF file should be created", gifFiles?.isNotEmpty() == true)
        
        // 7. Verify GIF file properties
        val latestGif = gifFiles?.maxByOrNull { it.lastModified() }
        assertNotNull("Should have a GIF file", latestGif)
        assertTrue("GIF should have reasonable size", latestGif!!.length() > 1000)
    }
    
    @Test
    fun overlay_visualization_during_capture() {
        // Start in camera view
        composeTestRule.onNodeWithContentDescription("Camera preview")
            .assertIsDisplayed()
        
        // Start capture
        composeTestRule.onNodeWithContentDescription("Start capture")
            .performClick()
        
        // Enable alpha overlay during capture
        composeTestRule.onNodeWithContentDescription("Toggle alpha overlay")
            .performClick()
        
        // Verify overlay is visible
        composeTestRule.onNodeWithContentDescription("Alpha channel heatmap overlay")
            .assertIsDisplayed()
        
        // Enable delta-E overlay
        composeTestRule.onNodeWithContentDescription("Toggle delta-E overlay")
            .performClick()
        
        // Verify both overlays work
        composeTestRule.onNodeWithContentDescription("Delta-E error heatmap")
            .assertIsDisplayed()
        
        // Toggle off overlays
        composeTestRule.onNodeWithContentDescription("Toggle alpha overlay")
            .performClick()
        composeTestRule.onNodeWithContentDescription("Toggle delta-E overlay")
            .performClick()
        
        // Wait for capture to complete
        composeTestRule.waitUntil(timeoutMillis = 5000) {
            try {
                composeTestRule.onNodeWithText("Frames: 81").fetchSemanticsNode()
                true
            } catch (e: AssertionError) {
                false
            }
        }
    }
    
    @Test
    fun technical_readout_detailed_view_workflow() {
        // Start capture
        composeTestRule.onNodeWithContentDescription("Start capture")
            .performClick()
        
        // Expand technical readout to detailed view
        composeTestRule.onNodeWithContentDescription("Expand details")
            .performClick()
        
        // Verify detailed metrics are shown
        composeTestRule.onNodeWithContentDescription("Collapse details")
            .assertIsDisplayed()
        
        // Performance graph should be visible in detailed mode
        // (Exact test depends on PerformanceGraph implementation)
        
        // Let some frames process
        Thread.sleep(2000)
        
        // Verify FPS is updating
        composeTestRule.waitUntil(timeoutMillis = 3000) {
            try {
                // Look for non-zero FPS value
                composeTestRule.onAllNodesWithText(text = "FPS:", substring = true)
                    .fetchSemanticsNodes()
                    .any { node -> 
                        node.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text)
                            ?.any { it.text.contains("FPS:") && !it.text.contains("0.0") } == true
                    }
            } catch (e: Exception) {
                false
            }
        }
        
        // Collapse back to compact view
        composeTestRule.onNodeWithContentDescription("Collapse details")
            .performClick()
        
        composeTestRule.onNodeWithContentDescription("Expand details")
            .assertIsDisplayed()
    }
    
    @Test
    fun info_panel_educational_content() {
        // Open info panel
        composeTestRule.onNodeWithContentDescription("Show pipeline information")
            .performClick()
        
        // Verify info content is displayed
        composeTestRule.onNodeWithText("Pipeline Overview", substring = true)
            .assertIsDisplayed()
        
        // Scroll through info content to test all sections
        composeTestRule.onNodeWithText("Go Neural Network", substring = true)
            .assertIsDisplayed()
        
        // Close info panel
        composeTestRule.onNodeWithContentDescription("Close pipeline information")
            .performClick()
        
        // Verify panel is closed
        composeTestRule.onNodeWithContentDescription("Show pipeline information")
            .assertIsDisplayed()
    }
    
    @Test
    fun error_recovery_workflow() {
        // This test would simulate error conditions and verify recovery
        
        // Start capture
        composeTestRule.onNodeWithContentDescription("Start capture")
            .performClick()
        
        // Simulate device rotation or app backgrounding
        device.setOrientationNatural()
        Thread.sleep(500)
        device.setOrientationLeft()
        Thread.sleep(500)
        device.setOrientationNatural()
        
        // App should recover gracefully
        composeTestRule.onNodeWithContentDescription("Camera preview")
            .assertIsDisplayed()
        
        // Technical readout should still be updating
        Thread.sleep(1000)
        
        // Verify app continues to function
        composeTestRule.onNodeWithContentDescription("Toggle alpha overlay")
            .performClick()
    }
    
    @Test
    fun multiple_capture_sessions() {
        repeat(3) { session ->
            // Start new capture session
            composeTestRule.onNodeWithContentDescription("Start capture")
                .performClick()
            
            // Wait for some frames
            Thread.sleep(2000)
            
            // Stop capture early (test partial captures)
            composeTestRule.onNodeWithContentDescription("Stop capture")
                .performClick()
            
            // Verify state resets properly
            composeTestRule.waitUntil(timeoutMillis = 1000) {
                try {
                    composeTestRule.onNodeWithContentDescription("Start capture").fetchSemanticsNode()
                    true
                } catch (e: AssertionError) {
                    false
                }
            }
        }
        
        // Final complete capture
        composeTestRule.onNodeWithContentDescription("Start capture")
            .performClick()
        
        composeTestRule.waitUntil(timeoutMillis = 5000) {
            try {
                composeTestRule.onNodeWithText("Frames: 81").fetchSemanticsNode()
                true
            } catch (e: AssertionError) {
                false
            }
        }
    }
    
    private fun grantCameraPermission() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        androidx.test.platform.app.InstrumentationRegistry
            .getInstrumentation()
            .uiAutomation
            .grantRuntimePermission(
                context.packageName,
                android.Manifest.permission.CAMERA
            )
    }
}
