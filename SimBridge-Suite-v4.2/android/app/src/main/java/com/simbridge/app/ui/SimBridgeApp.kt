package com.simbridge.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.simbridge.app.i18n.AppLang
import com.simbridge.app.i18n.UiStrings
import com.simbridge.app.i18n.stringsFor
import com.simbridge.app.ui.components.ConnectionHeroCard
import com.simbridge.app.ui.components.QrCodeGenerator
import com.simbridge.app.ui.components.AuroraBackground
import com.simbridge.app.ui.components.GlassSurface
import com.simbridge.app.ui.components.NeonButton
import com.simbridge.app.ui.components.NeonSwitch
import com.simbridge.app.ui.components.glass
import com.simbridge.app.ui.components.neonBorder
import com.simbridge.app.ui.theme.BridgeColors

enum class InternetShareMode { OFF, WIFI, USB }

enum class AudioMethodOption(val code: Int, val label: String) {
    AUTO_TX(1, "Auto (Telephony TX / Fallback)"),
    QCOM_INCALL(2, "Qualcomm (incall_music)"),
    MTK_BGS(3, "MediaTek (BGS)"),
    A13_REDIRECT(4, "Android 13 (MODE_CALL_REDIRECT)")
}

data class AppUiState(
    val lang: AppLang = AppLang.EN,
    val bluetoothEnabled: Boolean = true,
    val isConnected: Boolean = false,
    val iphoneName: String = "iPhone",
    val callAudioReady: Boolean = false,
    val audioMethod: AudioMethodOption = AudioMethodOption.AUTO_TX,
    val permissionsGranted: Boolean = false,
    val batteryExempt: Boolean = false,
    val internetMode: InternetShareMode = InternetShareMode.OFF,
    val vpnActive: Boolean = false,
    val routeThroughVpn: Boolean = true,
    val localSitesDirect: Boolean = true,
    val setupUrl: String = "",
    val hotspotSsid: String = "",
    val hotspotPass: String = "",
    val pairPayload: String = "simbridge://pair?v=2",
    val wdPairingPort: Int = -1,
    val wdConnectPort: Int = -1,
    val probeResultText: String = "",
    val audioRxPkts: Long = 0L,
    val audioTxPkts: Long = 0L,
    val companionStatusText: String = ""
)

@Composable
fun SimBridgeApp(
    state: AppUiState,
    onLangChange: (AppLang) -> Unit,
    onAudioMethodChange: (AudioMethodOption) -> Unit,
    onRunAudioProbe: () -> Unit,
    onInternetModeChange: (InternetShareMode) -> Unit,
    onHotspotCredsChange: (ssid: String, pass: String) -> Unit,
    onToggleRouteVpn: (Boolean) -> Unit,
    onToggleLocalDirect: (Boolean) -> Unit,
    onOpenHotspotSettings: () -> Unit,
    onRequestPermissions: () -> Unit,
    onRequestBatteryExemption: () -> Unit,
    onStartWirelessDebugFlow: () -> Unit,
    onReconnectShell: () -> Unit,
    onOpenDevOptions: () -> Unit,
    onRotateBleKey: () -> Unit,
    onStopService: () -> Unit
) {
    val strings = remember(state.lang) { stringsFor(state.lang) }
    val layoutDir = if (state.lang.isRtl) LayoutDirection.Rtl else LayoutDirection.Ltr
    var selectedTab by remember { mutableIntStateOf(0) }

    var showConnectAnotherDialog by remember { mutableStateOf(false) }
    var showPairQrDialog by remember { mutableStateOf(false) }
    var showInternetQrDialog by remember { mutableStateOf(false) }
    var showLanguageDialog by remember { mutableStateOf(false) }
    var showAudioMethodDialog by remember { mutableStateOf(false) }
    var showGuideDialog by remember { mutableStateOf(false) }
    var showWirelessDebugDialog by remember { mutableStateOf(false) }

    CompositionLocalProvider(LocalLayoutDirection provides layoutDir) {
        AuroraBackground(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                TopBrandHeader(
                    strings = strings,
                    onHelpClick = { showGuideDialog = true }
                )

                Box(modifier = Modifier.weight(1f)) {
                    when (selectedTab) {
                        0 -> HomeTabContent(
                            strings = strings,
                            state = state,
                            onCallAudioClick = {
                                if (state.callAudioReady) {
                                    showAudioMethodDialog = true
                                } else {
                                    onStartWirelessDebugFlow()
                                    showWirelessDebugDialog = true
                                }
                            },
                            onPermissionsClick = onRequestPermissions
                        )
                        1 -> InternetTabContent(
                            strings = strings,
                            state = state,
                            onModeChange = { mode ->
                                onInternetModeChange(mode)
                                if (mode != InternetShareMode.OFF) {
                                    showInternetQrDialog = true
                                }
                            },
                            onHotspotCredsChange = onHotspotCredsChange,
                            onToggleVpn = onToggleRouteVpn,
                            onToggleLocalDirect = onToggleLocalDirect,
                            onShowInternetQr = { showInternetQrDialog = true },
                            onOpenHotspotSettings = onOpenHotspotSettings
                        )
                        2 -> SettingsTabContent(
                            strings = strings,
                            state = state,
                            onPairedClick = {
                                if (state.isConnected) showConnectAnotherDialog = true
                                else showPairQrDialog = true
                            },
                            onCallAudioClick = { showAudioMethodDialog = true },
                            onLanguageClick = { showLanguageDialog = true },
                            onGuideClick = { showGuideDialog = true },
                            onPermissionsClick = onRequestPermissions,
                            onBatteryClick = onRequestBatteryExemption,
                            onWirelessDebugClick = {
                                onStartWirelessDebugFlow()
                                showWirelessDebugDialog = true
                            },
                            onStopClick = onStopService
                        )
                        3 -> SupportTabContent(
                            strings = strings,
                            state = state,
                            onGuideClick = { showGuideDialog = true },
                            onCallAudioClick = { showAudioMethodDialog = true },
                            onPermissionsClick = onRequestPermissions,
                            onWirelessDebugClick = {
                                onStartWirelessDebugFlow()
                                showWirelessDebugDialog = true
                            }
                        )
                    }
                }
            }

            FloatingBottomBar(
                strings = strings,
                selectedTab = selectedTab,
                onTabSelect = { selectedTab = it },
                onQrClick = {
                    if (selectedTab == 1 && state.internetMode != InternetShareMode.OFF) {
                        showInternetQrDialog = true
                    } else if (state.isConnected) {
                        showConnectAnotherDialog = true
                    } else {
                        showPairQrDialog = true
                    }
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 16.dp, vertical = 14.dp)
            )
        }

        if (showConnectAnotherDialog) {
            ConnectAnotherDialog(
                strings = strings,
                onDismiss = { showConnectAnotherDialog = false },
                onShowCode = {
                    onRotateBleKey()
                    showConnectAnotherDialog = false
                    showPairQrDialog = true
                }
            )
        }

        if (showPairQrDialog) {
            QrCodeModalDialog(
                title = strings.qrPairTitle,
                description = strings.qrPairDesc,
                payload = state.pairPayload,
                codeCaption = "BLE GATT • UDP :8119 • AES-256-GCM",
                closeLabel = strings.close,
                onDismiss = { showPairQrDialog = false }
            )
        }

        if (showInternetQrDialog && state.setupUrl.isNotBlank()) {
            QrCodeModalDialog(
                title = strings.qrInternetTitle,
                description = strings.qrInternetDesc,
                payload = state.setupUrl,
                codeCaption = state.setupUrl,
                closeLabel = strings.close,
                onDismiss = { showInternetQrDialog = false }
            )
        }

        if (showLanguageDialog) {
            LanguagePickerDialog(
                strings = strings,
                currentLang = state.lang,
                onDismiss = { showLanguageDialog = false },
                onSelect = {
                    onLangChange(it)
                    showLanguageDialog = false
                }
            )
        }

        if (showAudioMethodDialog) {
            AudioMethodPickerDialog(
                title = strings.audioMethodTitle,
                probeLabel = strings.runAudioProbe,
                probeHint = strings.probeHint,
                probeResult = state.probeResultText,
                companionStatus = state.companionStatusText,
                current = state.audioMethod,
                onRunProbe = onRunAudioProbe,
                onDismiss = { showAudioMethodDialog = false },
                onSelect = { onAudioMethodChange(it) }
            )
        }

        if (showGuideDialog) {
            InfoActionDialog(
                icon = Icons.Rounded.HelpOutline,
                title = strings.guideTitle,
                body = strings.guideBody,
                primaryLabel = strings.close,
                onDismiss = { showGuideDialog = false },
                onPrimary = { showGuideDialog = false }
            )
        }

        if (showWirelessDebugDialog) {
            val portDetails = buildString {
                if (state.wdPairingPort > 0) append("\n\nPairing port: ${state.wdPairingPort}")
                if (state.wdConnectPort > 0) append("\nConnect port: ${state.wdConnectPort}")
            }
            InfoActionDialog(
                icon = Icons.Rounded.DeveloperMode,
                title = strings.wdWaitingTitle,
                body = strings.wdWaitingDesc + portDetails,
                primaryLabel = strings.openDevOptions,
                secondaryLabel = if (state.wdConnectPort > 0) strings.reconnectShell else strings.cancel,
                onDismiss = {
                    if (state.wdConnectPort > 0) onReconnectShell()
                    showWirelessDebugDialog = false
                },
                onPrimary = {
                    showWirelessDebugDialog = false
                    onOpenDevOptions()
                }
            )
        }
    }
}

@Composable
private fun TopBrandHeader(strings: UiStrings, onHelpClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF0E1020))
                .neonBorder(RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Rounded.PhoneIphone, contentDescription = null, tint = BridgeColors.NeonCyan, modifier = Modifier.size(22.dp))
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(strings.appName, color = BridgeColors.TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(strings.appTagline, color = BridgeColors.TextSecondary, fontSize = 12.sp)
        }
        Box(
            modifier = Modifier
                .size(38.dp)
                .glass(CircleShape)
                .clickable(onClick = onHelpClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Rounded.HelpOutline, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun HomeTabContent(
    strings: UiStrings,
    state: AppUiState,
    onCallAudioClick: () -> Unit,
    onPermissionsClick: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 6.dp, bottom = 104.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            val title = when {
                !state.bluetoothEnabled -> strings.btOffTitle
                state.isConnected -> strings.connectedTo(state.iphoneName)
                else -> strings.notPairedTitle
            }
            Text(
                text = title,
                color = BridgeColors.TextPrimary,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
            )
        }

        if (!state.permissionsGranted) {
            item {
                GlassSurface(
                    shape = RoundedCornerShape(22.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Text(strings.permissionsRow, color = BridgeColors.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(strings.permissionsMissing, color = BridgeColors.TextSecondary, fontSize = 13.sp)
                        Spacer(modifier = Modifier.height(14.dp))
                        Button(
                            onClick = onPermissionsClick,
                            shape = RoundedCornerShape(50),
                            colors = ButtonDefaults.buttonColors(containerColor = BridgeColors.PurplePrimary),
                            modifier = Modifier.fillMaxWidth().height(48.dp)
                        ) {
                            Text(strings.grantPerms, color = Color.White, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }

        item {
            ConnectionHeroCard(
                strings = strings,
                isConnected = state.isConnected,
                bluetoothEnabled = state.bluetoothEnabled,
                iphoneName = state.iphoneName,
                callAudioReady = state.callAudioReady,
                onCallAudioChipClick = onCallAudioClick
            )
        }

        item {
            Text(
                text = strings.workingOnIphone,
                color = BridgeColors.TextSecondary,
                fontSize = 13.sp,
                modifier = Modifier.padding(start = 6.dp, top = 6.dp)
            )
        }

        item {
            ReadyFeatureCard(
                icon = Icons.Rounded.Call,
                title = strings.calls,
                statusText = if (state.permissionsGranted) strings.ready else strings.needsSetup,
                isReady = state.permissionsGranted,
                onClick = if (!state.permissionsGranted) onPermissionsClick else onCallAudioClick
            )
        }

        item {
            ReadyFeatureCard(
                icon = Icons.Rounded.SwapHoriz,
                title = strings.textMessages,
                statusText = if (state.permissionsGranted) strings.ready else strings.needsSetup,
                isReady = state.permissionsGranted,
                onClick = if (!state.permissionsGranted) onPermissionsClick else null
            )
        }
    }
}

@Composable
private fun InternetTabContent(
    strings: UiStrings,
    state: AppUiState,
    onModeChange: (InternetShareMode) -> Unit,
    onHotspotCredsChange: (String, String) -> Unit,
    onToggleVpn: (Boolean) -> Unit,
    onToggleLocalDirect: (Boolean) -> Unit,
    onShowInternetQr: () -> Unit,
    onOpenHotspotSettings: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 6.dp, bottom = 104.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Text(
                text = strings.openInternet,
                color = BridgeColors.TextPrimary,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
            )
        }
        item {
            GlassSurface(
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(BridgeColors.CardElevated),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Rounded.Public, contentDescription = null, tint = Color(0xFF9EA8FF), modifier = Modifier.size(24.dp))
                        }
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            val modeLabel = when (state.internetMode) {
                                InternetShareMode.OFF -> strings.modeOff
                                InternetShareMode.WIFI -> strings.modeWifi
                                InternetShareMode.USB -> strings.modeUsb
                            }
                            Text(modeLabel, color = BridgeColors.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                            if (state.internetMode != InternetShareMode.OFF && state.setupUrl.isNotBlank()) {
                                Text(
                                    state.setupUrl,
                                    color = BridgeColors.MintReady,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    maxLines = 1
                                )
                            }
                        }
                        if (state.internetMode != InternetShareMode.OFF) {
                            IconButton(onClick = onShowInternetQr) {
                                Icon(Icons.Rounded.QrCode2, contentDescription = null, tint = BridgeColors.PurplePrimary)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = strings.internetDesc,
                        color = BridgeColors.TextSecondary,
                        fontSize = 13.sp,
                        lineHeight = 19.sp
                    )

                    Spacer(modifier = Modifier.height(18.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val modes = listOf(
                            InternetShareMode.OFF to strings.modeOff,
                            InternetShareMode.WIFI to strings.modeWifi,
                            InternetShareMode.USB to strings.modeUsb
                        )
                        modes.forEach { (mode, label) ->
                            val selected = state.internetMode == mode
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .clip(RoundedCornerShape(50))
                                    .background(if (selected) BridgeColors.PurplePrimary else BridgeColors.CardElevated)
                                    .clickable { onModeChange(mode) },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = label,
                                    color = Color.White,
                                    fontSize = 14.sp,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                                )
                            }
                        }
                    }

                    if (state.internetMode == InternetShareMode.WIFI) {
                        Spacer(modifier = Modifier.height(14.dp))
                        OutlinedTextField(
                            value = state.hotspotSsid,
                            onValueChange = { onHotspotCredsChange(it, state.hotspotPass) },
                            label = { Text(strings.hotspotSsidLabel, fontSize = 12.sp) },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = state.hotspotPass,
                            onValueChange = { onHotspotCredsChange(state.hotspotSsid, it) },
                            label = { Text(strings.hotspotPassLabel, fontSize = 12.sp) },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    if (state.internetMode != InternetShareMode.OFF) {
                        Spacer(modifier = Modifier.height(14.dp))
                        OutlinedButton(
                            onClick = onOpenHotspotSettings,
                            shape = RoundedCornerShape(50),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(strings.openHotspotSettings, color = Color(0xFFBAC4FF))
                        }
                    }

                    Spacer(modifier = Modifier.height(22.dp))

                    PeachSwitchRow(
                        title = strings.secureConnTitle,
                        subtitle = if (state.vpnActive) strings.secureConnActive else strings.secureConnNone,
                        checked = state.routeThroughVpn,
                        onCheckedChange = onToggleVpn
                    )
                    Spacer(modifier = Modifier.height(18.dp))

                    PeachSwitchRow(
                        title = strings.localDirectTitle,
                        subtitle = strings.localDirectSub,
                        checked = state.localSitesDirect,
                        onCheckedChange = onToggleLocalDirect
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsTabContent(
    strings: UiStrings,
    state: AppUiState,
    onPairedClick: () -> Unit,
    onCallAudioClick: () -> Unit,
    onLanguageClick: () -> Unit,
    onGuideClick: () -> Unit,
    onPermissionsClick: () -> Unit,
    onBatteryClick: () -> Unit,
    onWirelessDebugClick: () -> Unit,
    onStopClick: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 6.dp, bottom = 104.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Text(
                text = strings.settings,
                color = BridgeColors.TextPrimary,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
            )
        }
        item {
            SettingsCardRow(
                icon = Icons.Rounded.Smartphone,
                iconBg = BridgeColors.CardElevated,
                iconTint = BridgeColors.TextSecondary,
                title = strings.pairedIphone,
                subtitle = if (state.isConnected) strings.connectedNow else strings.waitingIphone,
                subtitleColor = if (state.isConnected) BridgeColors.MintReady else BridgeColors.TextSecondary,
                onClick = onPairedClick
            )
        }
        item {
            SettingsCardRow(
                icon = Icons.Rounded.Call,
                iconBg = BridgeColors.MintIconBg,
                iconTint = BridgeColors.MintReady,
                title = strings.callAudio,
                subtitle = state.audioMethod.label,
                onClick = onCallAudioClick
            )
        }
        item { Spacer(modifier = Modifier.height(6.dp)) }
        item {
            SettingsCardRow(
                icon = Icons.Rounded.Language,
                iconBg = BridgeColors.CardElevated,
                iconTint = BridgeColors.TextSecondary,
                title = strings.language,
                subtitle = state.lang.displayName,
                onClick = onLanguageClick
            )
        }
        item {
            SettingsCardRow(
                icon = Icons.Rounded.HelpOutline,
                iconBg = BridgeColors.CardElevated,
                iconTint = BridgeColors.TextSecondary,
                title = strings.guide,
                onClick = onGuideClick
            )
        }
        item {
            SettingsCardRow(
                icon = Icons.Rounded.VerifiedUser,
                iconBg = BridgeColors.CardElevated,
                iconTint = if (state.permissionsGranted) BridgeColors.MintReady else BridgeColors.TextSecondary,
                title = strings.permissionsRow,
                subtitle = if (state.permissionsGranted) strings.permissionsOk else strings.permissionsMissing,
                onClick = onPermissionsClick
            )
        }
        item {
            SettingsCardRow(
                icon = Icons.Rounded.BatteryChargingFull,
                iconBg = BridgeColors.CardElevated,
                iconTint = if (state.batteryExempt) BridgeColors.MintReady else BridgeColors.TextSecondary,
                title = strings.batteryRow,
                subtitle = if (state.batteryExempt) strings.batteryOk else strings.batteryNeeded,
                onClick = onBatteryClick
            )
        }
        item {
            SettingsCardRow(
                icon = Icons.Rounded.Code,
                iconBg = BridgeColors.CardElevated,
                iconTint = if (state.callAudioReady) BridgeColors.MintReady else BridgeColors.TextSecondary,
                title = strings.wirelessDebug,
                subtitle = if (state.callAudioReady) strings.wirelessDebugReady else strings.wirelessDebugSub,
                onClick = onWirelessDebugClick
            )
        }
        item {
            SettingsCardRow(
                icon = Icons.Rounded.Close,
                iconBg = BridgeColors.RedIconBg,
                iconTint = BridgeColors.RedDanger,
                title = strings.stopApp,
                onClick = onStopClick
            )
        }
    }
}

@Composable
private fun SupportTabContent(
    strings: UiStrings,
    state: AppUiState,
    onGuideClick: () -> Unit,
    onCallAudioClick: () -> Unit,
    onPermissionsClick: () -> Unit,
    onWirelessDebugClick: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 6.dp, bottom = 104.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Text(
                text = strings.navSupport,
                color = BridgeColors.TextPrimary,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
            )
        }
        item {
            SettingsCardRow(
                icon = Icons.Rounded.HelpOutline,
                iconBg = BridgeColors.NeonViolet.copy(alpha = 0.14f),
                iconTint = BridgeColors.NeonViolet,
                title = strings.guideTitle,
                onClick = onGuideClick
            )
        }
        item {
            SettingsCardRow(
                icon = Icons.Rounded.Call,
                iconBg = BridgeColors.NeonCyan.copy(alpha = 0.12f),
                iconTint = BridgeColors.NeonCyan,
                title = strings.audioMethodTitle,
                subtitle = state.audioMethod.label,
                onClick = onCallAudioClick
            )
        }
        item {
            SettingsCardRow(
                icon = Icons.Rounded.Settings,
                iconBg = BridgeColors.NeonMint.copy(alpha = 0.12f),
                iconTint = BridgeColors.NeonMint,
                title = strings.wirelessDebug,
                subtitle = strings.wirelessDebugSub,
                onClick = onWirelessDebugClick
            )
        }
        item {
            SettingsCardRow(
                icon = Icons.Rounded.Lock,
                iconBg = BridgeColors.NeonMagenta.copy(alpha = 0.12f),
                iconTint = BridgeColors.NeonMagenta,
                title = strings.permissionsRow,
                onClick = onPermissionsClick
            )
        }
    }
}

@Composable
private fun FloatingBottomBar(
    strings: UiStrings,
    selectedTab: Int,
    onTabSelect: (Int) -> Unit,
    onQrClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        GlassSurface(
            shape = RoundedCornerShape(50),
            modifier = Modifier.weight(1f).height(64.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxSize().padding(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val tabs = listOf(
                    Triple(0, Icons.Rounded.Home, strings.navHome),
                    Triple(1, Icons.Rounded.Public, strings.navInternet),
                    Triple(2, Icons.Rounded.Settings, strings.navSettings),
                    Triple(3, Icons.Rounded.SupportAgent, strings.navSupport)
                )
                tabs.forEach { (index, icon, label) ->
                    val selected = selectedTab == index
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(50))
                            .background(
                                if (selected) Brush.linearGradient(
                                    listOf(BridgeColors.NeonViolet.copy(alpha = 0.38f), BridgeColors.NeonCyan.copy(alpha = 0.20f))
                                ) else Brush.linearGradient(listOf(Color.Transparent, Color.Transparent))
                            )
                            .clickable { onTabSelect(index) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = label,
                            tint = if (selected) Color.White else BridgeColors.TextSecondary,
                            modifier = Modifier.size(22.dp)
                        )
                        Text(
                            text = label,
                            fontSize = 11.sp,
                            color = if (selected) Color.White else BridgeColors.TextSecondary
                        )
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(Color(0xFF17143A))
                .neonBorder(CircleShape)
                .clickable(onClick = onQrClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Rounded.QrCode2, contentDescription = "QR", tint = BridgeColors.NeonCyan, modifier = Modifier.size(28.dp))
        }
    }
}

@Composable
private fun ReadyFeatureCard(
    icon: ImageVector,
    title: String,
    statusText: String,
    isReady: Boolean,
    onClick: (() -> Unit)? = null
) {
    GlassSurface(
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(BridgeColors.CardElevated),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = Color(0xFFBAC4FF), modifier = Modifier.size(20.dp))
            }
            Spacer(modifier = Modifier.width(14.dp))
            Text(title, color = BridgeColors.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Text(
                text = statusText,
                color = if (isReady) BridgeColors.MintReady else BridgeColors.TextSecondary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun SettingsCardRow(
    icon: ImageVector,
    iconBg: Color,
    iconTint: Color,
    title: String,
    subtitle: String? = null,
    subtitleColor: Color = BridgeColors.TextSecondary,
    onClick: () -> Unit
) {
    GlassSurface(
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(iconBg),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(20.dp))
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, color = BridgeColors.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                if (subtitle != null) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(subtitle, color = subtitleColor, fontSize = 13.sp)
                }
            }
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = BridgeColors.TextSecondary
            )
        }
    }
}

@Composable
private fun PeachSwitchRow(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, color = BridgeColors.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Spacer(modifier = Modifier.height(3.dp))
            Text(subtitle, color = BridgeColors.TextSecondary, fontSize = 12.sp, lineHeight = 17.sp)
        }
        NeonSwitch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun ConnectAnotherDialog(strings: UiStrings, onDismiss: () -> Unit, onShowCode: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(26.dp), color = BridgeColors.ModalSurface) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier.size(54.dp).clip(CircleShape).background(BridgeColors.NavSelectedBg),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.SwapHoriz, contentDescription = null, tint = Color(0xFFBAC4FF), modifier = Modifier.size(26.dp))
                }
                Spacer(modifier = Modifier.height(16.dp))
                Text(strings.connectAnotherTitle, color = BridgeColors.TextPrimary, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    strings.connectAnotherDesc,
                    color = BridgeColors.TextSecondary,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(22.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(strings.cancel, color = Color(0xFFBAC4FF))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = onShowCode,
                        shape = RoundedCornerShape(50),
                        colors = ButtonDefaults.buttonColors(containerColor = BridgeColors.PurplePrimary)
                    ) {
                        Text(strings.showCode, color = Color.White, modifier = Modifier.padding(horizontal = 8.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun QrCodeModalDialog(
    title: String,
    description: String,
    payload: String,
    codeCaption: String,
    closeLabel: String,
    onDismiss: () -> Unit
) {
    val qrBitmap = remember(payload) { QrCodeGenerator.generate(payload, 540) }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(26.dp), color = BridgeColors.ModalSurface) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(title, color = BridgeColors.TextPrimary, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    description,
                    color = BridgeColors.TextSecondary,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(18.dp))
                if (qrBitmap != null) {
                    Box(
                        modifier = Modifier
                            .size(220.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .background(Color.White)
                            .padding(12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            bitmap = qrBitmap.asImageBitmap(),
                            contentDescription = "QR Code",
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    codeCaption,
                    color = BridgeColors.MintReady,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(18.dp))
                Button(
                    onClick = onDismiss,
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.buttonColors(containerColor = BridgeColors.PurplePrimary),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(closeLabel, color = Color.White)
                }
            }
        }
    }
}

@Composable
private fun LanguagePickerDialog(
    strings: UiStrings,
    currentLang: AppLang,
    onDismiss: () -> Unit,
    onSelect: (AppLang) -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(24.dp), color = BridgeColors.ModalSurface) {
            Column(modifier = Modifier.padding(22.dp)) {
                Text(strings.selectLanguage, color = BridgeColors.TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(14.dp))
                AppLang.entries.forEach { lang ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .clickable { onSelect(lang) }
                            .padding(vertical = 12.dp, horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = lang == currentLang, onClick = { onSelect(lang) })
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(lang.displayName, color = BridgeColors.TextPrimary, fontSize = 15.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun AudioMethodPickerDialog(
    title: String,
    probeLabel: String,
    probeHint: String,
    probeResult: String,
    companionStatus: String,
    current: AudioMethodOption,
    onRunProbe: () -> Unit,
    onDismiss: () -> Unit,
    onSelect: (AudioMethodOption) -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(24.dp), color = BridgeColors.ModalSurface) {
            Column(modifier = Modifier.padding(22.dp)) {
                Text(title, color = BridgeColors.TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(12.dp))
                AudioMethodOption.entries.forEach { opt ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .clickable { onSelect(opt) }
                            .padding(vertical = 8.dp, horizontal = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = opt == current, onClick = { onSelect(opt) })
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(opt.label, color = BridgeColors.TextPrimary, fontSize = 13.sp)
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(probeHint, color = BridgeColors.TextSecondary, fontSize = 12.sp, lineHeight = 17.sp)
                Spacer(modifier = Modifier.height(10.dp))
                Button(
                    onClick = onRunProbe,
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.buttonColors(containerColor = BridgeColors.PurplePrimary),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(probeLabel, color = Color.White, fontSize = 13.sp)
                }
                if (companionStatus.isNotBlank()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        "CompanionInCallService: $companionStatus",
                        color = BridgeColors.TextSecondary,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 15.sp
                    )
                }
                if (probeResult.isNotBlank()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        probeResult,
                        color = BridgeColors.MintReady,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 16.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun InfoActionDialog(
    icon: ImageVector,
    title: String,
    body: String,
    primaryLabel: String,
    secondaryLabel: String? = null,
    onDismiss: () -> Unit,
    onPrimary: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(26.dp), color = BridgeColors.ModalSurface) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier.size(50.dp).clip(CircleShape).background(BridgeColors.NavSelectedBg),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icon, contentDescription = null, tint = Color(0xFFBAC4FF), modifier = Modifier.size(24.dp))
                }
                Spacer(modifier = Modifier.height(14.dp))
                Text(title, color = BridgeColors.TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                Spacer(modifier = Modifier.height(10.dp))
                Text(body, color = BridgeColors.TextSecondary, fontSize = 13.sp, lineHeight = 20.sp)
                Spacer(modifier = Modifier.height(20.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (secondaryLabel != null) {
                        TextButton(onClick = onDismiss) {
                            Text(secondaryLabel, color = Color(0xFFBAC4FF))
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Button(
                        onClick = onPrimary,
                        shape = RoundedCornerShape(50),
                        colors = ButtonDefaults.buttonColors(containerColor = BridgeColors.PurplePrimary)
                    ) {
                        Text(primaryLabel, color = Color.White)
                    }
                }
            }
        }
    }
}
