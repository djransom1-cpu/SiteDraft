package com.example.sitedraft.ui.theme

import androidx.compose.ui.graphics.Color

val SafetyOrange = Color(0xFFF47B20)
val SafetyOrangeContainer = Color(0xFFFFDBC6)
val OnSafetyOrangeContainer = Color(0xFF331200)

val ArchitecturalBlue = Color(0xFF003366) // Architectural Blue as requested
val ArchitecturalBlueContainer = Color(0xFF004080)
val OnArchitecturalBlueContainer = Color(0xFFFFFFFF)

val BlueprintWhite = Color(0xFFFFFFFF)
val BlueprintLine = Color(0xFFFFFFFF).copy(alpha = 0.2f)

val NeutralDark = Color(0xFF1A1C1E)
val NeutralLight = Color(0xFFF1F0F4)

// Container shades for text fields, menus, the zoom bar and dialogs. Without these the scheme
// kept Material's light-grey defaults, so white text sat on light grey (the project name field,
// the zoom percentage). Ordered dark to light around ArchitecturalBlue / ArchitecturalBlueContainer.
val BlueDeep = Color(0xFF002A55)
val BlueMid = Color(0xFF003A73)
val BlueRaised = Color(0xFF0A4D91)
// Field borders and dividers that still show on blue (Material's grey outline did not).
val BlueOutline = Color(0xFF9DBBDD)
val BlueOutlineVariant = Color(0xFF3F6B99)
// Error text has to read on blue: Material's dark red (0xFFB3261E) does not.
val ErrorOnBlue = Color(0xFFFFB4AB)
val OnErrorOnBlue = Color(0xFF690005)
val ErrorContainerOnBlue = Color(0xFF93000A)
val OnErrorContainerOnBlue = Color(0xFFFFDAD6)
