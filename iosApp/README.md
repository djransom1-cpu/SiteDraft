# SiteDraft for iOS

The iOS app is a thin SwiftUI shell around the shared Compose Multiplatform module
(`:shared`, iOS targets `iosArm64` and `iosSimulatorArm64`, framework name `Shared`).

It can only be built on a Mac with Xcode installed; the Windows machine the rest of the
project is developed on cannot compile Kotlin/Native for iOS or run Xcode.

## Building on a Mac

1. Install Xcode (15 or newer) and open a terminal in the project root.
2. Build the shared framework once so Xcode can see it:

       ./gradlew :shared:linkDebugFrameworkIosSimulatorArm64

3. Create the Xcode project (one-time): in Android Studio with the Kotlin Multiplatform
   plugin, use File > New > Kotlin Multiplatform App and point the `iosApp` module at this
   folder, or create an iOS App in Xcode named `iosApp` in this folder, delete its generated
   `ContentView.swift`/`iOSAppApp.swift`, add the two Swift files here, and add a
   "Run Script" build phase before "Compile Sources":

       cd "$SRCROOT/.."
       ./gradlew :shared:embedAndSignAppleFrameworkForXcode

   Then add `$(SRCROOT)/../shared/build/xcode-frameworks/$(CONFIGURATION)/$(SDK_NAME)`
   to Framework Search Paths and set "Other Linker Flags" to `$(inherited) -framework Shared`.
4. Run on a simulator or an iPad.

## What works on iOS

Everything in the shared module: drawing, symbols, floor plans, elevations, saving to the
app's Documents folder, PDF export (uncompressed image PDF). Not yet wired: file import
(image/PDF/DXF) through the document picker, Apple Pencil double-tap, and the Bosch laser
(Bluetooth is Android-only).
