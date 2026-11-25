package com.simbridge.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object BridgeColors {
    val Background = Color(0xFF07080F)
    val NeonCyan = Color(0xFF22D3EE)
    val NeonViolet = Color(0xFF8B7BFF)
    val NeonMagenta = Color(0xFFF0449E)
    val NeonMint = Color(0xFF5EEAD4)
    val Amber = Color(0xFFFFB84D)
    val CardSurface = Color(0xFF151722)
    val CardElevated = Color(0xFF1E2130)
    val NavPillBg = Color(0xFF171926)
    val NavSelectedBg = Color(0xFF282B42)
    val PurplePrimary = Color(0xFF7B68EE)
    val PurpleGradientEnd = Color(0xFF5340D6)
    val MintReady = Color(0xFF4FD1A5)
    val MintIconBg = Color(0xFF152D2C)
    val PeachSwitch = Color(0xFFF4A982)
    val PeachTrack = Color(0xFF4D3E3A)
    val RedDanger = Color(0xFFE05252)
    val RedIconBg = Color(0xFF2D191E)
    val TextPrimary = Color(0xFFF2F4F8)
    val TextSecondary = Color(0xFFA9AFC4)
    val ModalSurface = Color(0xFF1A1C2B)
}

private val AppDarkScheme = darkColorScheme(
    primary = BridgeColors.NeonViolet,
    background = BridgeColors.Background,
    surface = BridgeColors.CardSurface,
    onPrimary = Color.White,
    onBackground = BridgeColors.TextPrimary,
    onSurface = BridgeColors.TextPrimary,
    onSurfaceVariant = BridgeColors.TextSecondary
)

@Composable
fun SimBridgeTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = AppDarkScheme, content = content)
}
