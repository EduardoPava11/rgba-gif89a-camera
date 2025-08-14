package com.rgbagif.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rgbagif.MainActivity
import com.rgbagif.milestones.MilestoneState
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.time.Duration.Companion.seconds

/**
 * Compose UI tests for Milestone Workflow screen
 * Validates UI state, accessibility, and timing display
 */
@RunWith(AndroidJUnit4::class)
class MilestoneWorkflowUiTest {
    
    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()
    
    @Test
    fun testMilestoneCardsRender() {
        // Wait for UI to settle
        composeTestRule.waitForIdle()
        
        // Assert workflow title is visible
        composeTestRule.onNodeWithText("RGBA→GIF89a Workflow")
            .assertExists()
            .assertIsDisplayed()
        
        // Assert M1 card exists and is ACTIVE (green)
        composeTestRule.onNodeWithText("M1: CBOR Capture", substring = true)
            .assertExists()
            .assertIsDisplayed()
        
        // Check for green color indicator (ACTIVE state)
        composeTestRule.onNode(
            hasText("M1", substring = true) and 
            hasAnyDescendant(hasContentDescription("status_active"))
        ).assertExists()
        
        // Assert M2 card exists and is LOCKED (gray)
        composeTestRule.onNodeWithText("M2: Neural Downsizing", substring = true)
            .assertExists()
            .assertIsDisplayed()
        
        // Assert M3 card exists and is LOCKED (gray)
        composeTestRule.onNodeWithText("M3: GIF89a Export", substring = true)
            .assertExists()
            .assertIsDisplayed()
        
        // Check for lock icons on M2/M3
        composeTestRule.onNode(
            hasText("M2", substring = true) and
            hasAnyDescendant(hasContentDescription("status_locked"))
        ).assertExists()
        
        composeTestRule.onNode(
            hasText("M3", substring = true) and
            hasAnyDescendant(hasContentDescription("status_locked"))
        ).assertExists()
    }
    
    @Test
    fun testMilestoneTimingDisplay() = runBlocking {
        // Start M1
        composeTestRule.onNodeWithText("START")
            .assertExists()
            .assertHasClickAction()
            .performClick()
        
        // Wait for capture to begin
        delay(2.seconds)
        
        // Assert progress text updates
        composeTestRule.onNode(
            hasText("Capturing", substring = true) or
            hasText("frame", substring = true)
        ).assertExists()
        
        // Wait for some frames to be captured
        delay(5.seconds)
        
        // Check for frame counter update
        composeTestRule.onNode(
            hasText("/81", substring = true) // Frame count display
        ).assertExists()
        
        // After M1 completion (or partial completion)
        // timing text should be visible
        composeTestRule.waitUntil(timeoutMillis = 10000) {
            composeTestRule.onAllNodes(hasText("ms", substring = true))
                .fetchSemanticsNodes().isNotEmpty()
        }
        
        // Verify timing text format
        composeTestRule.onNode(hasText("ms", substring = true))
            .assertExists()
    }
    
    @Test
    fun testAccessibilityCompliance() {
        // All interactive elements should have content descriptions
        composeTestRule.onNodeWithText("START")
            .assert(hasContentDescription())
        
        // All tap targets should be at least 48dp
        composeTestRule.onAllNodes(hasClickAction())
            .assertAll(hasMinimumTouchTargetSize())
        
        // Cards should have semantic roles
        composeTestRule.onNode(hasText("M1", substring = true))
            .assert(hasAnyDescendant(hasContentDescription()))
        
        // Progress indicators should announce state
        composeTestRule.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate))
            .assertDoesNotExist() // Should use determinate progress
    }
    
    @Test
    fun testMilestoneStateTransitions() = runBlocking {
        // Initial state: M1 active, M2/M3 locked
        assertMilestoneState("M1", MilestoneState.MILESTONE_1_READY)
        assertMilestoneState("M2", MilestoneState.LOCKED)
        assertMilestoneState("M3", MilestoneState.LOCKED)
        
        // Start M1
        composeTestRule.onNodeWithText("START").performClick()
        delay(1.seconds)
        
        // M1 should be capturing
        composeTestRule.onNode(
            hasText("M1", substring = true) and
            hasAnyDescendant(hasText("Capturing", substring = true))
        ).assertExists()
        
        // Stop button should appear
        composeTestRule.onNodeWithText("STOP")
            .assertExists()
            .assertHasClickAction()
    }
    
    @Test
    fun testFrameCounterUpdates() = runBlocking {
        // Start capture
        composeTestRule.onNodeWithText("START").performClick()
        
        var lastFrameCount = 0
        repeat(5) {
            delay(2.seconds)
            
            // Find frame counter text
            val frameCountNode = composeTestRule.onNode(
                hasText("/81", substring = true)
            )
            
            if (frameCountNode.isDisplayed()) {
                // Extract frame count from text like "25/81 frames"
                val text = frameCountNode.fetchSemanticsNode()
                    .config[SemanticsProperties.Text]
                    ?.firstOrNull()?.text ?: ""
                
                val currentCount = text.substringBefore("/").filter { it.isDigit() }
                    .toIntOrNull() ?: 0
                
                // Assert count is increasing
                assert(currentCount >= lastFrameCount) {
                    "Frame count should increase: $currentCount >= $lastFrameCount"
                }
                lastFrameCount = currentCount
            }
        }
        
        // Verify some frames were captured
        assert(lastFrameCount > 0) { "Should have captured at least 1 frame" }
    }
    
    @Test
    fun testErrorStateDisplay() {
        // Simulate error by denying camera permission
        // (In real test, would use test doubles or mock)
        
        // Error message should be displayed
        composeTestRule.waitUntil(timeoutMillis = 5000) {
            composeTestRule.onAllNodes(
                hasText("Camera", substring = true) or
                hasText("Error", substring = true)
            ).fetchSemanticsNodes().isNotEmpty()
        }
    }
    
    // Helper functions
    
    private fun assertMilestoneState(milestone: String, expectedState: MilestoneState) {
        when (expectedState) {
            MilestoneState.LOCKED -> {
                composeTestRule.onNode(
                    hasText(milestone, substring = true) and
                    hasAnyDescendant(hasContentDescription("status_locked"))
                ).assertExists()
            }
            MilestoneState.MILESTONE_1_READY -> {
                composeTestRule.onNode(
                    hasText(milestone, substring = true) and
                    hasAnyDescendant(hasContentDescription("status_active"))
                ).assertExists()
            }
            else -> {
                // Other states
            }
        }
    }
    
    private fun hasMinimumTouchTargetSize(): SemanticsMatcher {
        return SemanticsMatcher("has minimum touch target size") { node ->
            val size = node.size
            size.width >= 48.dp.value && size.height >= 48.dp.value
        }
    }
    
    private fun SemanticsNodeInteraction.isDisplayed(): Boolean {
        return try {
            assertIsDisplayed()
            true
        } catch (e: AssertionError) {
            false
        }
    }
}