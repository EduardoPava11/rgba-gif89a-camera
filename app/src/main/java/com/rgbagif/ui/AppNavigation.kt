package com.rgbagif.ui

import androidx.compose.runtime.*
import androidx.compose.ui.graphics.ImageBitmap
import com.rgbagif.camera.CameraXManager
// import com.rgbagif.m1.M1VerificationScreen // Temporarily disabled
import com.rgbagif.ui.browser.FrameBrowserScreen
import com.rgbagif.milestones.MilestoneWorkflowScreen
import com.rgbagif.utils.GifFrameExtractor
import kotlinx.coroutines.launch
import java.io.File

/**
 * Main app navigation container with milestone-based workflow
 */
@Composable
fun AppNavigation(
    cameraManager: CameraXManager,
    exportManager: com.rgbagif.export.GifExportManager
) {
    var currentScreen by remember { mutableStateOf(Screen.TECHNICAL_PIPELINE) }
    var sessionId by remember { mutableStateOf<String?>(null) }
    var gifFile by remember { mutableStateOf<File?>(null) }
    var quantizedFrames by remember { mutableStateOf<List<ImageBitmap>>(emptyList()) }
    val scope = rememberCoroutineScope()
    
    when (currentScreen) {
        Screen.TECHNICAL_PIPELINE -> {
            // TechnicalPipelineScreen temporarily disabled
            SimpleGifScreen(
                cameraManager = cameraManager,
                exportManager = exportManager
            )
        }
        Screen.SIMPLE_GIF -> {
            SimpleGifScreen(
                cameraManager = cameraManager,
                exportManager = exportManager
            )
        }
        Screen.M1_VERIFICATION -> {
            // M1VerificationScreen temporarily disabled
            SimpleGifScreen(
                cameraManager = cameraManager,
                exportManager = exportManager
            )
        }
        Screen.MILESTONE_WORKFLOW -> {
            MilestoneWorkflowScreen(
                cameraManager = cameraManager,
                onNavigateToM1Verification = {
                    currentScreen = Screen.M1_VERIFICATION
                },
                onNavigateToFrameBrowser = { id ->
                    sessionId = id
                    currentScreen = Screen.FRAME_BROWSER
                },
                onGifCreated = { file ->
                    android.util.Log.d("AppNavigation", "onGifCreated called with file: ${file.absolutePath}")
                    gifFile = file
                    currentScreen = Screen.EXPORT
                    android.util.Log.d("AppNavigation", "currentScreen set to EXPORT")
                }
            )
        }
        Screen.FRAME_BROWSER -> {
            FrameBrowserScreen(
                sessionId = sessionId,
                onBack = {
                    currentScreen = Screen.MILESTONE_WORKFLOW
                }
            )
        }
        Screen.EXPORT -> {
            ExportScreen(
                gifFile = gifFile,
                exportManager = exportManager,
                onBack = {
                    currentScreen = Screen.MILESTONE_WORKFLOW
                }
            )
        }
        Screen.CUBE_VISUALIZATION -> {
            // CubeVisualizationScreen temporarily disabled
            SimpleGifScreen(
                cameraManager = cameraManager,
                exportManager = exportManager
            )
        }
    }
}

enum class Screen {
    TECHNICAL_PIPELINE,
    SIMPLE_GIF,
    M1_VERIFICATION,
    MILESTONE_WORKFLOW,
    FRAME_BROWSER,
    EXPORT,
    CUBE_VISUALIZATION
}
