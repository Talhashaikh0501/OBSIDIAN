
package com.obsidian.assistant.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val ObsidianColorScheme = darkColorScheme(
    primary = ObsidianSilver,
    onPrimary = ObsidianBlack,
    primaryContainer = ObsidianGraphite,
    onPrimaryContainer = ObsidianTextPrimary,

    secondary = ObsidianIce,
    onSecondary = ObsidianBlack,
    secondaryContainer = ObsidianSurface,
    onSecondaryContainer = ObsidianSilver,

    background = ObsidianBlack,
    onBackground = ObsidianTextPrimary,

    surface = ObsidianSurface,
    onSurface = ObsidianTextPrimary,

    surfaceVariant = ObsidianGraphite,
    onSurfaceVariant = ObsidianTextSecondary,

    outline = ObsidianBorder,
    error = ObsidianSteel,
    onError = ObsidianBlack
)

@Composable
fun OBSIDIANTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ObsidianColorScheme,
        typography = Typography,
        content = content
    )
}
