package com.rgbagif.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Form follows function: Square shapes dominate the design system.
 * Minimal corner radius only where necessary for visual hierarchy.
 */

// Core shape definitions
val SquareShape = RoundedCornerShape(0.dp)        // Pure square - the foundation
val MicroRoundedShape = RoundedCornerShape(2.dp)  // Subtle softening for touch targets
val SmallRoundedShape = RoundedCornerShape(4.dp)  // Functional rounding where needed

// Material 3 shape system - emphasizing squares
val SquareShapes = Shapes(
    extraSmall = SquareShape,         // Pure square - no rounding
    small = MicroRoundedShape,        // Minimal 2dp for small elements
    medium = SmallRoundedShape,       // 4dp max for cards/dialogs
    large = SmallRoundedShape,        // Consistent 4dp maximum
    extraLarge = SmallRoundedShape    // No excessive rounding ever
)

// Component-specific shapes
val ButtonShape = SquareShape              // Cubic buttons - pure squares
val CardShape = SquareShape                // Square cards and surfaces
val OverlayShape = MicroRoundedShape       // 2dp for overlay readability
val DialogShape = SmallRoundedShape        // 4dp for floating elements
val ChipShape = MicroRoundedShape          // 2dp for selection chips