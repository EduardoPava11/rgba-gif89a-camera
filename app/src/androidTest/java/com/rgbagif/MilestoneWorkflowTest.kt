package com.rgbagif

import android.Manifest
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.rgbagif.MainActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Connected test for milestone workflow and camera integration
 */
@RunWith(AndroidJUnit4::class)
class MilestoneWorkflowTest {
    
    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()
    
    @get:Rule
    val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.CAMERA
    )
    
    @Test
    fun testWorkflowScreenIsFirstScreen() {
        // Verify the workflow title is shown
        composeTestRule.onNodeWithText("RGBA→GIF89a Workflow")
            .assertIsDisplayed()
        
        composeTestRule.onNodeWithText("3-Milestone Pipeline")
            .assertIsDisplayed()
    }
    
    @Test
    fun testCameraPreviewStartsWithPermission() {
        // Wait for camera to initialize
        runBlocking { delay(2000) }
        
        // Verify camera preview area exists (the PreviewPanel)
        // Since AndroidView doesn't have testTag by default, we check for parent Card
        composeTestRule.onNode(
            hasTestTag("preview_panel").or(
                hasContentDescription("Camera Preview")
            )
        ).assertExists()
    }
    
    @Test
    fun testCaptureButtonIsAccessible() {
        // Wait for UI to stabilize
        runBlocking { delay(1000) }
        
        // Find and verify capture button
        composeTestRule.onNodeWithText("START CAPTURE")
            .assertIsDisplayed()
            .assertHasClickAction()
    }
    
    @Test
    fun testMilestoneCardsAreDisplayed() {
        // Check all three milestone cards
        composeTestRule.onNodeWithText("M1: CBOR Capture")
            .assertIsDisplayed()
        
        composeTestRule.onNodeWithText("M2: Neural Downsize")
            .assertIsDisplayed()
        
        composeTestRule.onNodeWithText("M3: GIF89a Export")
            .assertIsDisplayed()
    }
    
    @Test
    fun testCaptureCreatesSessionDirectory() {
        // Start capture
        composeTestRule.onNodeWithText("START CAPTURE").performClick()
        
        // Wait for at least one frame
        runBlocking { delay(3000) }
        
        // Check that session directory was created
        val capturedFramesDir = File(
            composeTestRule.activity.getExternalFilesDir(null),
            "captured_frames"
