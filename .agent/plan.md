# Project Plan

SiteDraft expansion: Adding scalability, symbol libraries, improved object manipulation, and precision dimensioning for construction drafting.

## Project Brief

# SiteDraft Project Brief

SiteDraft is a professional-grade construction drafting application designed to bridge the gap between field sketching and precision engineering. This expansion focuses on providing a scalable, high-fidelity drafting environment with integrated symbol libraries and precision tools for construction professionals.

## Features
- **Scalable Precision Canvas**: A technical workspace supporting real-world units (Metric/Imperial), customizable scale factors, and smooth zoom/pan for detailed drafting.
- **Integrated Symbol Library**: A categorized repository of standard construction symbols (Electrical, Plumbing, Welding, and Steel shapes) for rapid floor plan development.
- **Advanced Object Manipulation**: Robust "Select & Transform" tools allowing users to move, resize, and rotate any object or library symbol with pixel-perfect precision.
- **Dynamic Dimensioning**: Real-time input and display of exact line lengths and object dimensions, ensuring all drafts meet construction-ready specifications.

## High-Level Technical Stack
- **Kotlin**: The primary language for modern, safe, and concise Android development.
- **Jetpack Compose**: Used for building both the reactive UI components and the custom-drawn drafting canvas.
- **Jetpack Navigation 3**: A state-driven navigation strategy to manage the app's flow and complex UI states.
- **Compose Material Adaptive**: Ensures a seamless experience across all device form factors (phones, tablets, and foldables) using adaptive layout patterns.
- **Kotlin Coroutines**: Handles asynchronous tasks like symbol loading and complex geometric calculations without blocking the UI thread.

## UI Design Image
![UI Design](C:/Users/djran/AndroidStudioProjects/SiteDraft/input_images/sitedraft_ui_mockup.jpg)
Image path = C:/Users/djran/AndroidStudioProjects/SiteDraft/input_images/sitedraft_ui_mockup.jpg

## Implementation Steps
**Total Duration:** 1h 18m 24s

### Task_1_Setup_and_Canvas_Engine: Initialize project with Jetpack Navigation 3 and Compose Material Adaptive. Build the core sketching engine using Compose Canvas and GraphicsLayer.
- **Status:** COMPLETED
- **Duration:** 8m 36s

### Task_2_Object_Manipulation_and_Imports: Implement object selection, moving, and resizing for drawn elements. Add support for importing images, PDFs, and DXF files.
- **Status:** COMPLETED

### Task_5_SPen_Support: Add S Pen support: pressure sensitivity for pen strokes, handling the S Pen side button for quick eraser toggle, and implementing palm rejection.
- **Status:** COMPLETED
- **Duration:** 3m 3s

### Task_3_Persistence_and_Export: Implement local project saving and exporting to PDF and DXF formats.
- **Status:** COMPLETED
- **Duration:** 7m 16s

### Task_4_Run_and_Verify: Final polish (app icon, theme) and comprehensive verification of stability and requirements.
- **Status:** COMPLETED
- **Duration:** 24m 4s

### Task_6_Advanced_Drafting_Tools: Implement a scalable coordinate system (Metric/Imperial) with zoom/pan support. Integrate the categorized symbol library (Electrical, Plumbing, Welding, Steel). Enhance object manipulation with rotation and implement dynamic dimensioning for real-time length display and input.
- **Status:** COMPLETED
- **Updates:** Final refinement attempt: Disabled Dynamic Colors to force the Blueprint theme, implemented automatic dimensioning labels during drawing, and refactored the tablet UI into a non-overlapping split-pane layout. Scale/Unit logic was also made reactive.
- **Acceptance Criteria:**
  - Canvas supports Metric and Imperial scaling.
  - Symbol library is accessible and functional.
  - Objects and symbols can be rotated with precision.
  - Dynamic dimensioning shows exact lengths and accepts input.
  - The implemented UI must match C:/Users/djran/AndroidStudioProjects/SiteDraft/input_images/sitedraft_ui_mockup.jpg
- **Duration:** 10m 1s

### Task_7_Run_and_Verify_Expansion: Final polish and comprehensive verification of stability and requirements for the expansion features.
- **Status:** COMPLETED
- **Updates:** Expansion project complete. Successfully implemented Blueprint theme, automatic real-time dimensioning, integrated symbol library, and a non-overlapping adaptive tablet layout. Verified stability on tablet emulator. Note: Unit-to-grid alignment requires manual scale adjustment in the current version.
- **Acceptance Criteria:**
  - App is stable and free of crashes.
  - All new expansion features are verified.
  - UI is polished on phone and tablet.
- **Duration:** 25m 24s

