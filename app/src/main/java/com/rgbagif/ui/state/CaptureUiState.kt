package com.rgbagif.ui.state

import com.rgbagif.milestones.MilestoneState

/**
 * UI state for capture screen - Complete interface for CameraScreen
 */
data class CaptureUiState(
    val isCapturing: Boolean = false,
    val framesCaptured: Int = 0,
    val totalFrames: Int = 81,
    val targetFrames: Int = 81, // Alias for compatibility
    val fps: Float = 0f,
    val captureTimeMs: Long = 0L,
    val milestoneState: MilestoneState = MilestoneState.IDLE,
    val error: String? = null
)