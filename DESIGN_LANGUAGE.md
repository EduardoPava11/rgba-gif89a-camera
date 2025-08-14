# Cubic Design Language - RGBA→GIF89a Camera App

## Design Philosophy: "Form Follows Function"

The app's core function is capturing **square frames** (729×729) and processing them into **square GIFs** (81×81). The UI should reflect this geometric constraint through a consistent **cubic/square aesthetic**.

---

## Color Palette

### Primary Colors
- **MatrixGreen40** (`#00B366`) - Primary action color (capture, process)
- **ProcessingOrange** (`#FF6B35`) - Active state indicator
- **ErrorRed** (`#DC2626`) - Error states and stop actions
- **PixelBlue40** (`#0891B2`) - Secondary accents

### Supporting Colors
- **GridDark** (`#0A0A0A`) - Deep backgrounds, grid lines
- **GridLight** (`#F8F9FA`) - High contrast text
- **CubeGrey40** (`#6B7280`) - Secondary surfaces

---

## Key UI Components

### 1. **Square Camera Preview**
```kotlin
// Force 1:1 aspect ratio to match 729×729 capture
Box(
    modifier = Modifier
        .fillMaxWidth()
        .aspectRatio(1f) // Critical: square preview
        .clip(RoundedCornerShape(12.dp))
        .border(
            width = if (capturing) 3.dp else 2.dp,
            color = if (capturing) ProcessingOrange else MatrixGreen40
        )
)
```

**Design Rationale:**
- Square preview matches the 729×729 input exactly
- Sharp rounded corners (12.dp) maintain geometric precision
- Dynamic border color/width provides visual feedback

### 2. **Grid Progress Indicator (9×9)**
```kotlin
// 9×9 grid representing 81 total frames
@Composable
fun GridProgressIndicator(progress: Float) {
    repeat(9) { row ->
        Row {
            repeat(9) { col ->
                val frameIndex = row * 9 + col
                val isCompleted = frameIndex < (progress * 81).toInt()
                
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(
                            color = if (isCompleted) MatrixGreen40 else Color.Gray,
                            shape = RoundedCornerShape(1.dp)
                        )
                )
            }
        }
    }
}
```

**Design Rationale:**
- Visual representation of the 9×9 macrocell structure in the neural network
- Each pixel represents one of 81 frames
- Provides immediate visual feedback on capture progress

### 3. **Cubic Control Buttons**
```kotlin
@Composable
fun CubicButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier.size(64.dp), // Always square
    content: @Composable RowScope.() -> Unit
) {
    Button(
        onClick = onClick,
        modifier = modifier.aspectRatio(1f), // Force square
        shape = RoundedCornerShape(8.dp), // Sharp geometric corners
        border = BorderStroke(1.dp, MatrixGreen40),
        content = content
    )
}
```

**Design Rationale:**
- All buttons are square, reinforcing the geometric theme
- Consistent corner radius (8.dp) across all interactive elements
- Border emphasizes the cubic structure

### 4. **Technical Status Display**
```kotlin
Card(
    colors = CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)
    ),
    shape = RoundedCornerShape(8.dp)
) {
    Column {
        Text("CAPTURING: ${framesCaptured}/81 FRAMES")
        Text("729×729 → 81×81 @ 24fps") // Technical specifications visible
        LinearProgressIndicator(
            modifier = Modifier
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
        )
    }
}
```

**Design Rationale:**
- Exposes technical details (729×729 → 81×81) to reinforce the precision of the process
- Sharp, geometric progress bar
- High-contrast typography for readability

---

## Layout Structure

### Main Screen Layout
```
┌─────────────────────────────────────┐
│ [Status Bar - Only when capturing] │ ← Top overlay
│                                     │
│    ┌─────────────────────────┐      │
│    │                         │      │
│    │   Square Camera Preview │      │ ← Centered 1:1 aspect ratio
│    │       729×729           │      │
│    │                         │      │
│    └─────────────────────────┘      │
│                                     │
│ [Info] [CAPTURE/STOP] [Status]     │ ← Bottom controls (3 squares)
└─────────────────────────────────────┘
```

### Control Button Layout
```
┌────┐    ┌──────┐    ┌────┐
│ 81 │    │ GIF  │    │REC │  ← 3 square buttons
│frms│    │START │    │24fp│
└────┘    └──────┘    └────┘
  64dp       96dp       64dp   ← Sizes emphasize primary action
```

---

## Interactive States

### 1. **Idle State**
- Camera preview: Clean border (MatrixGreen40, 2.dp)
- Capture button: "GIF" + Play icon (MatrixGreen40)
- Side buttons: Technical info (81 frames, 24fps)

### 2. **Capturing State**
- Camera preview: Animated border (ProcessingOrange, 3.dp)
- Status bar: Visible with grid progress indicator
- Capture button: "STOP" + Stop icon (ErrorRed)
- Grid indicator: Real-time frame completion

### 3. **Completion State**
- Central overlay: "GIF COMPLETE!" message
- Technical summary: "81 frames captured, 729×729 → 81×81 @ 24fps"
- Subtle animation (optional): Grid completion celebration

### 4. **Error State**
- Error card: Sharp red background (ErrorRed)
- High-contrast white text on red
- Geometric shape maintained

---

## Typography

### Hierarchy
- **Headlines**: Bold, technical specifications visible
- **Body**: Clean, readable progress information  
- **Labels**: Monospace-inspired for technical data
- **Buttons**: Bold, uppercase for actions ("GIF", "STOP", "REC")

### Technical Information Display
- Always show resolution transformations: "729×729 → 81×81"
- Frame rate specification: "@ 24fps" 
- Frame counts: "Frame 45/81"
- Process state: "CAPTURING", "PROCESSING", "COMPLETE"

---

## Animation & Feedback

### 1. **Capture State Transitions**
- Border color change: Smooth transition between MatrixGreen40 ↔ ProcessingOrange
- Grid progress: Individual pixel completion (satisfying visual feedback)
- Button state: Color morph between green (start) and red (stop)

### 2. **Progress Indication**
- **Linear bar**: Traditional progress for familiarity
- **9×9 Grid**: Unique visual that connects to the neural network structure
- **Border pulse**: Subtle animation during capture

### 3. **Completion Feedback**
- Grid completion: Quick fill animation
- Success state: Brief geometric celebration
- Ready for next capture: Clear visual reset

---

## Accessibility

### 1. **Color Independence**
- All states work without color (border width, text, icons)
- High contrast ratios maintained
- Clear visual hierarchies

### 2. **Touch Targets**
- All buttons minimum 44dp (meets accessibility guidelines)
- Primary capture button 96dp (oversized for easy use)
- Clear spacing between interactive elements

### 3. **Screen Reader Support**
- Descriptive content descriptions
- State announcements ("Capturing frame 45 of 81")
- Technical specifications accessible

---

## Implementation Priority

### Phase 1: Core Cubic Structure
1. Square camera preview with dynamic borders
2. Square button layout (3-button bottom row)
3. Basic color scheme implementation

### Phase 2: Advanced Progress
1. 9×9 grid progress indicator
2. Technical status display
3. State-based animations

### Phase 3: Polish
1. Smooth transitions between states
2. Completion celebrations
3. Error state refinements

---

This design language ensures that every UI element reinforces the core function: transforming square camera input into square GIF output through a precise, technical process. The geometric consistency makes the app's purpose immediately clear while providing satisfying visual feedback throughout the capture process.
