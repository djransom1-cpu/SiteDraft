package com.example.sitedraft.navigation

import kotlinx.serialization.Serializable
import androidx.navigation3.runtime.NavKey

sealed interface SiteDraftNavKey : NavKey {
    @Serializable
    data object Main : SiteDraftNavKey
}
