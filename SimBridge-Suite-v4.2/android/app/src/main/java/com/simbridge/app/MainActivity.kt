package com.simbridge.app

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.simbridge.app.adb.AdbWirelessManager
import com.simbridge.app.bridge.BleCrypto
import com.simbridge.app.bridge.BridgeEventBus
import com.simbridge.app.call.CompanionInCallService
import com.simbridge.app.i18n.AppLang
import com.simbridge.app.net.OpenInternetServer
import com.simbridge.app.service.BridgeService
import com.simbridge.app.shell.ShellBinderProvider
import com.simbridge.app.ui.AppUiState
import com.simbridge.app.ui.AudioMethodOption
import com.simbridge.app.ui.InternetShareMode
import com.simbridge.app.ui.SimBridgeApp
import com.simbridge.app.ui.theme.SimBridgeTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private lateinit var adbManager: AdbWirelessManager
    private var uiState by mutableStateOf(AppUiState())

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        refreshSystemState()
        startBridgeService()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        adbManager = AdbWirelessManager(this)

        val prefs = getSharedPreferences("simbridge_prefs", Context.MODE_PRIVATE)
        val savedLang = if (prefs.getString("lang", "en") == "fa") AppLang.FA else AppLang.EN
        val savedSsid = prefs.getString("hotspot_ssid", "") ?: ""
        val savedPass = prefs.getString("hotspot_pass", "") ?: ""
        val savedMethodCode = prefs.getInt("audio_method", 1)
        val savedMethod = AudioMethodOption.entries.firstOrNull { it.code == savedMethodCode }
            ?: AudioMethodOption.AUTO_TX

        uiState = uiState.copy(
            lang = savedLang,
            audioMethod = savedMethod,
            hotspotSsid = savedSsid,
            hotspotPass = savedPass,
            pairPayload = buildPairPayload()
        )

        BridgeEventBus.addConnectionListener { connected, name ->
            runOnUiThread {
                uiState = uiState.copy(isConnected = connected, iphoneName = name)
            }
        }
        BridgeEventBus.addAudioStatsListener { rx, tx ->
            runOnUiThread { uiState = uiState.copy(audioRxPkts = rx, audioTxPkts = tx) }
        }
        BridgeEventBus.addShellListener { bound ->
            runOnUiThread {
                uiState = uiState.copy(
                    callAudioReady = bound,
                    companionStatusText = CompanionInCallService.statusSummary()
                )
            }
        }
        BridgeEventBus.addCompanionDiagListener {
            runOnUiThread {
                uiState = uiState.copy(companionStatusText = CompanionInCallService.statusSummary())
            }
        }

        prefs.edit().putBoolean("user_stopped", false).apply()
        startBridgeService()
        refreshSystemState()

        setContent {
            SimBridgeTheme {
                SimBridgeApp(
                    state = uiState,
                    onLangChange = { newLang ->
                        prefs.edit().putString("lang", newLang.code).apply()
                        uiState = uiState.copy(lang = newLang)
                    },
                    onRunAudioProbe = {
                        uiState = uiState.copy(probeResultText = "Probing hardware (2.5s)...")
                        lifecycleScope.launch {
                            val output = withContext(Dispatchers.IO) {
                                val tap = ShellBinderProvider.shellTap
                                if (tap == null) "Error: Shell service not bound. Pair Wireless Debugging first."
                                else runCatching { tap.probeCallAudio(uiState.audioMethod.code, 2500) }
                                    .getOrElse { "Probe error: ${it.message}" }
                            }
                            uiState = uiState.copy(probeResultText = output)
                        }
                    },
                    onAudioMethodChange = { method ->
                        prefs.edit().putInt("audio_method", method.code).apply()
                        uiState = uiState.copy(audioMethod = method)
                        val intent = Intent(this, BridgeService::class.java).apply {
                            action = BridgeService.ACTION_AUDIO_METHOD
                            putExtra("method", method.code)
                        }
                        ContextCompat.startForegroundService(this, intent)
                    },
                    onInternetModeChange = { mode ->
                        val enabled = mode != InternetShareMode.OFF
                        val url = OpenInternetServer(this).getSetupUrl()
                        uiState = uiState.copy(internetMode = mode, setupUrl = url)
                        sendInternetConfig(
                            enabled = enabled,
                            vpn = uiState.routeThroughVpn,
                            localDirect = uiState.localSitesDirect,
                            ssid = uiState.hotspotSsid,
                            pass = uiState.hotspotPass
                        )
                    },
                    onHotspotCredsChange = { ssid, pass ->
                        prefs.edit()
                            .putString("hotspot_ssid", ssid)
                            .putString("hotspot_pass", pass)
                            .apply()
                        uiState = uiState.copy(hotspotSsid = ssid, hotspotPass = pass)
                        if (uiState.internetMode != InternetShareMode.OFF) {
                            sendInternetConfig(true, uiState.routeThroughVpn, uiState.localSitesDirect, ssid, pass)
                        }
                    },
                    onToggleRouteVpn = { checked ->
                        uiState = uiState.copy(routeThroughVpn = checked)
                        if (uiState.internetMode != InternetShareMode.OFF) {
                            sendInternetConfig(true, checked, uiState.localSitesDirect, uiState.hotspotSsid, uiState.hotspotPass)
                        }
                    },
                    onToggleLocalDirect = { checked ->
                        uiState = uiState.copy(localSitesDirect = checked)
                        if (uiState.internetMode != InternetShareMode.OFF) {
                            sendInternetConfig(true, uiState.routeThroughVpn, checked, uiState.hotspotSsid, uiState.hotspotPass)
                        }
                    },
                    onOpenHotspotSettings = {
                        runCatching { startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS)) }
                    },
                    onRequestPermissions = {
                        permissionLauncher.launch(requiredPermissions())
                    },
                    onRequestBatteryExemption = {
                        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = Uri.parse("package:$packageName")
                        }
                        runCatching { startActivity(intent) }
                    },
                    onStartWirelessDebugFlow = {
                        adbManager.startDiscovery { pairPort, connPort ->
                            runOnUiThread {
                                uiState = uiState.copy(wdPairingPort = pairPort, wdConnectPort = connPort)
                            }
                        }
                    },
                    onReconnectShell = {
                        lifecycleScope.launch {
                            val res = adbManager.reconnectAndLaunchShell()
                            val msg = res.fold(
                                onSuccess = { it },
                                onFailure = { "Shell launch failed: ${it.message}" }
                            )
                            Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
                            refreshSystemState()
                        }
                    },
                    onOpenDevOptions = {
                        val devIntent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                        runCatching { startActivity(devIntent) }
                            .onFailure { startActivity(Intent(Settings.ACTION_SETTINGS)) }
                    },
                    onRotateBleKey = {
                        BleCrypto.rotateSharedKey(this)
                        uiState = uiState.copy(pairPayload = buildPairPayload(), isConnected = false)
                        val rotateIntent = Intent(this, BridgeService::class.java).apply {
                            action = BridgeService.ACTION_ROTATE_BLE_KEY
                        }
                        ContextCompat.startForegroundService(this, rotateIntent)
                    },
                    onStopService = {
                        prefs.edit().putBoolean("user_stopped", true).apply()
                        adbManager.stopDiscovery()
                        val stopIntent = Intent(this, BridgeService::class.java).apply {
                            action = BridgeService.ACTION_STOP_SERVICE
                        }
                        startService(stopIntent)
                        finish()
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshSystemState()
    }

    private fun buildPairPayload(): String {
        val k = BleCrypto.getSharedKeyBase64Url(this)
        return "simbridge://pair?v=4&svc=${BridgeService.SERVICE_UUID}&ap=${BridgeService.AUDIO_STREAM_PORT}&k=$k"
    }

    private fun startBridgeService() {
        val intent = Intent(this, BridgeService::class.java)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun sendInternetConfig(
        enabled: Boolean,
        vpn: Boolean,
        localDirect: Boolean,
        ssid: String,
        pass: String
    ) {
        val intent = Intent(this, BridgeService::class.java).apply {
            action = BridgeService.ACTION_INTERNET_CONFIG
            putExtra("enabled", enabled)
            putExtra("vpn", vpn)
            putExtra("local_direct", localDirect)
            putExtra("ssid", ssid)
            putExtra("pass", pass)
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun refreshSystemState() {
        val btManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        val btOn = btManager.adapter?.isEnabled == true

        val pm = getSystemService(PowerManager::class.java)
        val batteryOk = pm.isIgnoringBatteryOptimizations(packageName)

        val cm = getSystemService(ConnectivityManager::class.java)
        val vpnOn = cm.getNetworkCapabilities(cm.activeNetwork)
            ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true

        val permsOk = requiredPermissions().all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

        val url = BridgeService.lastSetupUrl.ifBlank { OpenInternetServer(this).getSetupUrl() }

        uiState = uiState.copy(
            bluetoothEnabled = btOn,
            batteryExempt = batteryOk,
            vpnActive = vpnOn,
            permissionsGranted = permsOk,
            setupUrl = url,
            callAudioReady = ShellBinderProvider.shellTap != null,
            companionStatusText = CompanionInCallService.statusSummary()
        )
    }

    private fun requiredPermissions(): Array<String> {
        val list = mutableListOf(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.ANSWER_PHONE_CALLS,
            Manifest.permission.READ_SMS,
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.READ_CALL_LOG
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            list.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            list.add(Manifest.permission.BLUETOOTH_CONNECT)
            list.add(Manifest.permission.BLUETOOTH_SCAN)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list.add(Manifest.permission.POST_NOTIFICATIONS)
            list.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        return list.toTypedArray()
    }
}
