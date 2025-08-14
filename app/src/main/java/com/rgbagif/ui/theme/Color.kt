package com.rgbagif.ui.theme

import androidx.compose.ui.graphics.Color

// Base Palette - Neutral tones for structure
val NeutralDark = Color(0xFF121212)     // Primary background
val NeutralMid = Color(0xFF424242)      // Disabled/inactive states
val NeutralLight = Color(0xFFE0E0E0)    // Text and borders

// Functional Colors - Purpose-driven hues
val ProcessingOrange = Color(0xFFFF6D00) // Active capture, processing
val MatrixGreen = Color(0xFF00C853)      // Success, completion, ready
val ErrorRed = Color(0xFFD50000)         // Errors, stop actions
val InfoBlue = Color(0xFF2962FF)         // Information, help

// Overlay Colors - Semi-transparent for layering
val AlphaOverlay = Color(0x66FF6D00)     // Alpha heatmap overlay
val DeltaEOverlay = Color(0x662962FF)    // Delta E heatmap overlay

// Light Theme Variants
val NeutralDarkLight = Color(0xFFF5F5F5)
val NeutralMidLight = Color(0xFFBDBDBD)
val NeutralLightDark = Color(0xFF212121)

// Additional colors referenced in code
val MatrixGreen40 = Color(0x6600C853)    // 40% opacity green
val GridLight = Color(0xFFE0E0E0)        // Grid lines
val PixelBlue40 = Color(0x662962FF)      // 40% opacity blue
val NeutralMedium = NeutralMid           // Alternative name
val SuccessGreen = MatrixGreen           // Alternative name for success state