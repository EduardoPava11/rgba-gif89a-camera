package com.rgbagif.milestones

/**
 * Milestone workflow states
 */
enum class MilestoneState {
    IDLE,
    MILESTONE_1_CAPTURING,      // Capturing 81 CBOR frames
    MILESTONE_1_GENERATING_PNG,  // Converting CBOR to PNG
    MILESTONE_1_COMPLETE,        // Ready to browse original frames
    MILESTONE_2_DOWNSIZING,      // Neural network downsizing
    MILESTONE_2_GENERATING_PNG,  // Converting downsized CBOR to PNG  
    MILESTONE_2_COMPLETE,        // Ready to browse downsized frames
    MILESTONE_3_QUANTIZING,      // Color quantizing to 256 colors
    MILESTONE_3_GENERATING_CBOR, // Converting quantized to CBOR
    MILESTONE_3_GENERATING_GIF,  // Creating GIF89a
    MILESTONE_3_COMPLETE,        // GIF export ready
    ERROR
}

/**
 * Progress tracking for each milestone
 */
data class MilestoneProgress(
    val milestone: Int = 1,
    val state: MilestoneState = MilestoneState.IDLE,
    val currentFrame: Int = 0,
    val totalFrames: Int = 81,
    val processingTimeMs: Long = 0,
    val message: String = "",
    val error: String? = null
)
