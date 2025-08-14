package com.rgbagif.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.rgbagif.camera.CameraXManager
import com.rgbagif.ui.screens.*
import java.io.File

@Composable
fun AppNavigation(
    cameraManager: CameraXManager,
    navController: NavHostController = rememberNavController()
) {
    NavHost(
        navController = navController,
        startDestination = "m1_capture"
    ) {
        // M1: RGBA Capture
        composable("m1_capture") {
            M1CaptureScreen(
                cameraManager = cameraManager,
                onNavigateToM2 = { sessionDir ->
                    navController.currentBackStackEntry?.savedStateHandle?.set("sessionDir", sessionDir.absolutePath)
                    navController.navigate("m2_processing")
                }
            )
        }
        
        // M2: Neural Downsize
        composable("m2_processing") {
            val sessionDirPath = navController.previousBackStackEntry?.savedStateHandle?.get<String>("sessionDir")
            sessionDirPath?.let { path ->
                M2ProcessingScreen(
                    sessionDir = File(path),
                    onNavigateToM3 = { sessionDir ->
                        navController.currentBackStackEntry?.savedStateHandle?.set("sessionDir", sessionDir.absolutePath)
                        navController.navigate("m3_export")
                    }
                )
            }
        }
        
        // M3: GIF Export
        composable("m3_export") {
            val sessionDirPath = navController.previousBackStackEntry?.savedStateHandle?.get<String>("sessionDir")
            sessionDirPath?.let { path ->
                M3GifExportScreen(
                    sessionDir = File(path),
                    onComplete = {
                        // Navigate back to start
                        navController.navigate("m1_capture") {
                            popUpTo("m1_capture") { inclusive = true }
                        }
                    }
                )
            }
        }
    }
}