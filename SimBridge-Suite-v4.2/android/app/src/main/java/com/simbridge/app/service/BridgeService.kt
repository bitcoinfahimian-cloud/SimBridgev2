package com.simbridge.app.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.*
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.ParcelUuid
import android.os.PowerManager
import android.telecom.TelecomManager
import android.telephony.PhoneStateListener
import android.telephony.SmsManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.simbridge.app.MainActivity
import com.simbridge.app.adb.AdbKeyStore
import com.simbridge.app.adb.AdbWirelessManager
import com.simbridge.app.bridge.BleCrypto
import com.simbridge.app.bridge.BleFraming
import com.simbridge.app.bridge.BridgeEventBus
import com.simbridge.app.bridge.CallAudioStreamServer
import com.simbridge.app.bridge.ContactResolver
import com.simbridge.app.call.CompanionInCallService
import com.simbridge.app.net.OpenInternetServer
import com.simbridge.app.shell.ShellBinderProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

class BridgeService : Service() {

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("8f2b0001-5b8f-4c2a-9d3e-2f6a1b0c9d01")
        val CHAR_CTRL_WRITE: UUID = UUID.fromString("8f2b0002-5b8f-4c2a-9d3e-2f6a1b0c9d01")
        val CHAR_CTRL_NOTIFY: UUID = UUID.fromString("8f2b0003-5b8f-4c2a-9d3e-2f6a1b0c9d01")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        const val CHANNEL_ID = "simbridge_foreground"
        const val AUDIO_STREAM_PORT = 8119
        const val ACTION_INTERNET_CONFIG = "com.simbridge.app.INTERNET_CONFIG"
        const val ACTION_AUDIO_METHOD = "com.simbridge.app.AUDIO_METHOD"
        const val ACTION_STOP_SERVICE = "com.simbridge.app.STOP_SERVICE"
        const val ACTION_ROTATE_BLE_KEY = "com.simbridge.app.ROTATE_BLE_KEY"

        @Volatile var isRunning: Boolean = false
        @Volatile var lastSetupUrl: String = ""
    }

    private var gattServer: BluetoothGattServer? = null
    private var notifyCharacteristic: BluetoothGattCharacteristic? = null
    private var advertiseCallback: AdvertiseCallback? = null
    private var btStateReceiver: BroadcastReceiver? = null
    private val bleFraming = BleFraming()
    private val bleNotifyMutex = Mutex()

    @Volatile private var negotiatedMtu: Int = 23
    private var connectedDevice: BluetoothDevice? = null
    private var authenticatedAddress: String? = null
    private var pendingChallenge: String? = null
    private var lastRxSeq: Long = 0L
    private val txSeq = AtomicLong(1L)

    private var telephonyCallback: Any? = null
    @Suppress("DEPRECATION")
    private var legacyPhoneListener: PhoneStateListener? = null
    private var dynamicPhoneStateReceiver: BroadcastReceiver? = null
    @Volatile private var lastKnownCallerNumber: String = ""
    @Volatile private var currentCallState: String = "IDLE"
    @Volatile private var preferredAudioMethod: Int = 1

    private val mainHandler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var callWakeLock: PowerManager.WakeLock? = null

    private var activeCallPipes: Array<ParcelFileDescriptor>? = null
    @Volatile private var audioOpenedForActiveState: Boolean = false
    private lateinit var audioStreamServer: CallAudioStreamServer
    private lateinit var internetServer: OpenInternetServer
    private lateinit var adbManager: AdbWirelessManager

    private val shellListener: (Boolean) -> Unit = { bound ->
        // Notify paired iPhone immediately when shell binds/unbinds
        sendEncryptedJsonToIphone(
            JSONObject().apply {
                put("type", "pong")
                put("audio_host", internetServer.detectBestLocalIp())
                put("audio_port", AUDIO_STREAM_PORT)
                put("call_audio_ready", bound)
                put("companion_registered", CompanionInCallService.registeredInTelecom)
                put("companion_bound", CompanionInCallService.isBound)
                put("companion_events_count", CompanionInCallService.eventsReceivedCount.get())
            }
        )
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        internetServer = OpenInternetServer(this, 8118)
        audioStreamServer = CallAudioStreamServer(this, AUDIO_STREAM_PORT)
        adbManager = AdbWirelessManager(this)
        loadSavedConfig()
        lastSetupUrl = internetServer.getSetupUrl()

        startForeground(1, buildNotification("SimBridge active"))
        tryStartBleGattServer()
        registerBluetoothStateReceiver()
        registerCallStateObserver()
        scheduleShellAutoReconnect()

        BridgeEventBus.onOutgoingToIphone = { json ->
            sendEncryptedJsonToIphone(json)
        }
        BridgeEventBus.onCompanionCallEvent = { state, number ->
            handleUnifiedCallState(state, number)
        }
        BridgeEventBus.addShellListener(shellListener)
    }

    private fun loadSavedConfig() {
        val prefs = getSharedPreferences("simbridge_prefs", MODE_PRIVATE)
        internetServer.hotspotSsid = prefs.getString("hotspot_ssid", "") ?: ""
        internetServer.hotspotPassword = prefs.getString("hotspot_pass", "") ?: ""
        preferredAudioMethod = prefs.getInt("audio_method", 1)
    }

    private fun scheduleShellAutoReconnect() {
        if (AdbKeyStore.getPinnedServerCertHex(this) == null) return
        if (ShellBinderProvider.shellTap != null) return

        adbManager.startDiscovery(showNotification = false) { _, connPort ->
            if (connPort > 0 && ShellBinderProvider.shellTap == null) {
                serviceScope.launch {
                    adbManager.reconnectAndLaunchShell()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_SERVICE -> {
                closeActiveCallAudio()
                unregisterCallStateObserver()
                unregisterBluetoothStateReceiver()
                BridgeEventBus.removeShellListener(shellListener)
                adbManager.stopDiscovery()
                internetServer.stop()
                stopBle()
                serviceScope.cancel()
                isRunning = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_ROTATE_BLE_KEY -> {
                authenticatedAddress = null
                pendingChallenge = null
                lastRxSeq = 0L
                txSeq.set(1L)
                bleFraming.resetRx()
                BridgeEventBus.notifyConnectionChanged(false, "iPhone")
            }
            ACTION_AUDIO_METHOD -> {
                preferredAudioMethod = intent.getIntExtra("method", 1)
            }
            ACTION_INTERNET_CONFIG -> {
                val enabled = intent.getBooleanExtra("enabled", false)
                internetServer.routeThroughVpn = intent.getBooleanExtra("vpn", true)
                internetServer.localSitesDirect = intent.getBooleanExtra("local_direct", true)
                intent.getStringExtra("ssid")?.let { internetServer.hotspotSsid = it }
                intent.getStringExtra("pass")?.let { internetServer.hotspotPassword = it }
                lastSetupUrl = internetServer.getSetupUrl()
                if (enabled) {
                    internetServer.start()
                } else {
                    internetServer.stop()
                }
            }
        }
        return START_STICKY
    }

    private fun registerBluetoothStateReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                if (state == BluetoothAdapter.STATE_ON) {
                    stopBle()
                    tryStartBleGattServer()
                } else if (state == BluetoothAdapter.STATE_OFF) {
                    stopBle()
                    BridgeEventBus.notifyConnectionChanged(false, "iPhone")
                }
            }
        }
        runCatching {
            ContextCompat.registerReceiver(
                this,
                receiver,
                IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            btStateReceiver = receiver
        }
    }

    private fun unregisterBluetoothStateReceiver() {
        btStateReceiver?.let {
            runCatching { unregisterReceiver(it) }
            btStateReceiver = null
        }
    }

    @SuppressLint("MissingPermission")
    private fun registerCallStateObserver() {
        val tm = getSystemService(TelephonyManager::class.java) ?: return

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
                @Suppress("DEPRECATION")
                val incomingNum = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
                if (!incomingNum.isNullOrBlank()) {
                    lastKnownCallerNumber = incomingNum
                }
            }
        }
        runCatching {
            ContextCompat.registerReceiver(
                this,
                receiver,
                IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            dynamicPhoneStateReceiver = receiver
        }

        // Fallback observer when CompanionInCallService is NOT currently bound
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                    override fun onCallStateChanged(state: Int) {
                        if (CompanionInCallService.isBound) return
                        when (state) {
                            TelephonyManager.CALL_STATE_RINGING -> {
                                mainHandler.postDelayed({
                                    if (!CompanionInCallService.isBound) {
                                        handleUnifiedCallState("RINGING", lastKnownCallerNumber)
                                    }
                                }, 450)
                            }
                            TelephonyManager.CALL_STATE_OFFHOOK -> {
                                handleUnifiedCallState("ACTIVE", lastKnownCallerNumber)
                            }
                            else -> handleUnifiedCallState("IDLE", "")
                        }
                    }
                }
                tm.registerTelephonyCallback(mainExecutor, cb)
                telephonyCallback = cb
            } else {
                @Suppress("DEPRECATION")
                val listener = object : PhoneStateListener() {
                    @Deprecated("Deprecated in Java")
                    override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                        if (CompanionInCallService.isBound) return
                        if (!phoneNumber.isNullOrBlank()) lastKnownCallerNumber = phoneNumber
                        val mapped = when (state) {
                            TelephonyManager.CALL_STATE_RINGING -> "RINGING"
                            TelephonyManager.CALL_STATE_OFFHOOK -> "ACTIVE"
                            else -> "IDLE"
                        }
                        handleUnifiedCallState(mapped, lastKnownCallerNumber)
                    }
                }
                @Suppress("DEPRECATION")
                tm.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
                legacyPhoneListener = listener
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun unregisterCallStateObserver() {
        dynamicPhoneStateReceiver?.let {
            runCatching { unregisterReceiver(it) }
            dynamicPhoneStateReceiver = null
        }
        val tm = getSystemService(TelephonyManager::class.java) ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (telephonyCallback as? TelephonyCallback)?.let { tm.unregisterTelephonyCallback(it) }
                telephonyCallback = null
            } else {
                @Suppress("DEPRECATION")
                legacyPhoneListener?.let { tm.listen(it, PhoneStateListener.LISTEN_NONE) }
                legacyPhoneListener = null
            }
        }
    }

    /**
     * Fix #3: Clean call audio lifecycle separation between DIALING, ACTIVE, and IDLE.
     * - DIALING: Acquires WakeLock only; does NOT lock AudioRecord/AudioTrack prematurely before
     *   telephony voice routing settles (so ACTIVE can always open cleanly on the real voice path).
     * - ACTIVE: Always opens (or cleanly reopens if a prior non-ACTIVE handle existed) shell call audio
     *   and starts UDP audio streaming.
     * - IDLE: Closes pipes & shell audio first (unblocking pipe readers), stops UDP server, releases WakeLock.
     */
    private fun handleUnifiedCallState(stateName: String, number: String) {
        val prevState = currentCallState
        if (number.isNotBlank()) lastKnownCallerNumber = number
        val effectiveNumber = if (stateName == "IDLE") "" else lastKnownCallerNumber
        currentCallState = stateName

        when (stateName) {
            "DIALING" -> {
                acquireCallWakeLock()
                // Do not pre-open shell audio during DIALING; wait until ACTIVE so telephony routing is established.
                if (activeCallPipes != null) {
                    closeActiveCallAudio()
                }
            }
            "ACTIVE" -> {
                acquireCallWakeLock()
                val needsReopen = (prevState == "DIALING") || !audioOpenedForActiveState || (activeCallPipes == null)
                if (needsReopen) {
                    openActiveCallAudio(forceReopen = true)
                }
            }
            "IDLE" -> {
                lastKnownCallerNumber = ""
                closeActiveCallAudio()
                releaseCallWakeLock()
            }
        }

        val callerName = if (effectiveNumber.isNotBlank()) {
            ContactResolver.lookupDisplayName(this, effectiveNumber)
        } else ""

        val audioActive = (activeCallPipes != null && audioOpenedForActiveState)
        val payload = JSONObject().apply {
            put("type", "call_state")
            put("state", stateName)
            put("number", effectiveNumber)
            put("caller_name", callerName)
            put("audio_active", audioActive)
            put("audio_host", internetServer.detectBestLocalIp())
            put("audio_port", AUDIO_STREAM_PORT)
        }
        sendEncryptedJsonToIphone(payload)
    }

    private fun acquireCallWakeLock() {
        if (callWakeLock?.isHeld == true) return
        val pm = getSystemService(PowerManager::class.java) ?: return
        callWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SimBridge::CallAudio").apply {
            acquire(3 * 60 * 60 * 1000L)
        }
    }

    private fun releaseCallWakeLock() {
        runCatching {
            if (callWakeLock?.isHeld == true) callWakeLock?.release()
        }
        callWakeLock = null
    }

    private fun openActiveCallAudio(forceReopen: Boolean = false) {
        val tap = ShellBinderProvider.shellTap ?: return
        if (forceReopen && activeCallPipes != null) {
            closeActiveCallAudio()
        }
        if (activeCallPipes != null) return
        runCatching {
            val pipes = tap.openCallAudio(preferredAudioMethod, 16000)
            activeCallPipes = pipes
            audioOpenedForActiveState = true
            audioStreamServer.start(pipes)
        }.onFailure {
            closeActiveCallAudio()
        }
    }

    private fun closeActiveCallAudio() {
        audioOpenedForActiveState = false
        // Close shell audio and FDs first so any blocked pipe reader receives EOF immediately
        runCatching { ShellBinderProvider.shellTap?.closeCallAudio() }
        activeCallPipes?.forEach { runCatching { it.close() } }
        activeCallPipes = null
        audioStreamServer.stop()
    }

    @SuppressLint("MissingPermission")
    private fun tryStartBleGattServer() {
        runCatching {
            val btManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
            val adapter = btManager.adapter ?: return
            if (!adapter.isEnabled) return

            gattServer = btManager.openGattServer(this, object : BluetoothGattServerCallback() {
                override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        connectedDevice = device
                        authenticatedAddress = null
                        lastRxSeq = 0L
                        txSeq.set(1L)
                        negotiatedMtu = 23
                        bleFraming.resetRx()
                        pendingChallenge = BleCrypto.randomNonceBase64(16)
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        if (connectedDevice?.address == device.address) {
                            connectedDevice = null
                            authenticatedAddress = null
                            pendingChallenge = null
                            lastRxSeq = 0L
                            bleFraming.resetRx()
                            BridgeEventBus.notifyConnectionChanged(false, "iPhone")
                        }
                    }
                }

                override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
                    if (device.address == connectedDevice?.address && mtu >= 23) {
                        negotiatedMtu = mtu
                    }
                }

                override fun onDescriptorWriteRequest(
                    device: BluetoothDevice,
                    requestId: Int,
                    descriptor: BluetoothGattDescriptor,
                    preparedWrite: Boolean,
                    responseNeeded: Boolean,
                    offset: Int,
                    value: ByteArray
                ) {
                    if (responseNeeded) {
                        gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                    }
                    val enabled = value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                    if (descriptor.uuid == CCCD_UUID && enabled && device.address == connectedDevice?.address) {
                        val nonce = pendingChallenge ?: BleCrypto.randomNonceBase64(16).also { pendingChallenge = it }
                        val challengeJson = JSONObject().apply {
                            put("type", "auth_challenge")
                            put("nonce", nonce)
                            put("ver", 4)
                        }.toString()
                        sendFramedNotification(device, challengeJson.toByteArray(Charsets.UTF_8), BleFraming.FLAG_PLAINTEXT)
                    }
                }

                override fun onCharacteristicWriteRequest(
                    device: BluetoothDevice,
                    requestId: Int,
                    characteristic: BluetoothGattCharacteristic,
                    preparedWrite: Boolean,
                    responseNeeded: Boolean,
                    offset: Int,
                    value: ByteArray
                ) {
                    if (responseNeeded) {
                        gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                    }
                    if (characteristic.uuid == CHAR_CTRL_WRITE) {
                        val assembled = bleFraming.decodeChunk(value) ?: return
                        val (flags, payload) = assembled
                        if (flags == BleFraming.FLAG_ENCRYPTED) {
                            handleEncryptedPacketFromDevice(device, payload)
                        }
                    }
                }
            })

            val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
            val writeChar = BluetoothGattCharacteristic(
                CHAR_CTRL_WRITE,
                BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                BluetoothGattCharacteristic.PERMISSION_WRITE
            )
            val notifyChar = BluetoothGattCharacteristic(
                CHAR_CTRL_NOTIFY,
                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_READ
            )
            notifyChar.addDescriptor(
                BluetoothGattDescriptor(
                    CCCD_UUID,
                    BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
                )
            )
            notifyCharacteristic = notifyChar
            service.addCharacteristic(writeChar)
            service.addCharacteristic(notifyChar)
            gattServer?.addService(service)

            val settings = AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setConnectable(true)
                .build()
            val data = AdvertiseData.Builder()
                .addServiceUuid(ParcelUuid(SERVICE_UUID))
                .build()
            val cb = object : AdvertiseCallback() {}
            advertiseCallback = cb
            adapter.bluetoothLeAdvertiser?.startAdvertising(settings, data, cb)
        }
    }

    @SuppressLint("MissingPermission")
    private fun handleEncryptedPacketFromDevice(device: BluetoothDevice, encryptedPacket: ByteArray) {
        val sharedKey = BleCrypto.getOrCreateSharedKey(this)
        val plainBytes = BleCrypto.decrypt(sharedKey, encryptedPacket) ?: return
        val json = runCatching { JSONObject(String(plainBytes, Charsets.UTF_8)) }.getOrNull() ?: return

        val type = json.optString("type")
        val seq = json.optLong("seq", -1L)

        if (authenticatedAddress != device.address) {
            if (type == "auth_response" && seq > 0L) {
                val nonce = json.optString("nonce")
                val clientNonce = json.optString("client_nonce")
                if (!pendingChallenge.isNullOrEmpty() && nonce == pendingChallenge && clientNonce.isNotBlank()) {
                    authenticatedAddress = device.address
                    pendingChallenge = null
                    lastRxSeq = seq
                    val name = json.optString("device_name").ifBlank {
                        runCatching { device.name }.getOrNull() ?: "iPhone"
                    }
                    BridgeEventBus.notifyConnectionChanged(true, name)
                    sendEncryptedJsonToIphone(
                        JSONObject().apply {
                            put("type", "auth_ok")
                            put("client_nonce", clientNonce)
                            put("audio_host", internetServer.detectBestLocalIp())
                            put("audio_port", AUDIO_STREAM_PORT)
                            put("call_audio_ready", ShellBinderProvider.shellTap != null)
                            put("companion_registered", CompanionInCallService.registeredInTelecom)
                            put("companion_bound", CompanionInCallService.isBound)
                            put("companion_events_count", CompanionInCallService.eventsReceivedCount.get())
                            put("android_model", Build.MODEL)
                        }
                    )
                }
            }
            return
        }

        if (seq <= lastRxSeq) return
        lastRxSeq = seq

        when (type) {
            "ping" -> {
                sendEncryptedJsonToIphone(
                    JSONObject().apply {
                        put("type", "pong")
                        put("audio_host", internetServer.detectBestLocalIp())
                        put("audio_port", AUDIO_STREAM_PORT)
                        put("call_audio_ready", ShellBinderProvider.shellTap != null)
                        put("companion_registered", CompanionInCallService.registeredInTelecom)
                        put("companion_bound", CompanionInCallService.isBound)
                        put("companion_events_count", CompanionInCallService.eventsReceivedCount.get())
                    }
                )
            }
            "send_sms" -> {
                val number = json.optString("to")
                val body = json.optString("body")
                if (number.isNotBlank() && body.isNotBlank()) {
                    val sms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        getSystemService(SmsManager::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        SmsManager.getDefault()
                    }
                    val parts = sms.divideMessage(body)
                    sms.sendMultipartTextMessage(number, null, parts, null, null)
                }
            }
            "dial" -> {
                val number = json.optString("number")
                if (number.isNotBlank()) {
                    lastKnownCallerNumber = number
                    val tm = getSystemService(TelecomManager::class.java)
                    tm.placeCall(Uri.fromParts("tel", number, null), null)
                }
            }
            "answer" -> {
                if (!CompanionInCallService.answerCall()) {
                    val tm = getSystemService(TelecomManager::class.java)
                    @Suppress("DEPRECATION")
                    tm.acceptRingingCall()
                }
            }
            "hangup" -> {
                if (!CompanionInCallService.hangupCall()) {
                    val tm = getSystemService(TelecomManager::class.java)
                    tm.endCall()
                }
            }
            "mute" -> {
                val muted = json.optBoolean("muted", false)
                CompanionInCallService.setCallMuted(muted)
            }
            "dtmf" -> {
                val digit = json.optString("digit").firstOrNull()
                if (digit != null) CompanionInCallService.sendDtmf(digit)
            }
            "probe_audio" -> {
                val method = json.optInt("method", preferredAudioMethod)
                serviceScope.launch {
                    val wasStreaming = (activeCallPipes != null && currentCallState == "ACTIVE")
                    if (wasStreaming) closeActiveCallAudio()

                    val tap = ShellBinderProvider.shellTap
                    val resultJson = if (tap != null) {
                        runCatching { JSONObject(tap.probeCallAudio(method, 2500)) }
                            .getOrElse { JSONObject().put("ok", false).put("error", it.message) }
                    } else {
                        JSONObject().put("ok", false).put("error", "shell_not_bound")
                    }
                    resultJson.put("type", "audio_probe_result")
                    sendEncryptedJsonToIphone(resultJson)

                    // Restore live call audio stream if a call is still ACTIVE
                    if (wasStreaming && currentCallState == "ACTIVE") {
                        openActiveCallAudio(forceReopen = true)
                    }
                }
            }
        }
    }

    private fun sendEncryptedJsonToIphone(json: JSONObject) {
        val dev = connectedDevice ?: return
        if (authenticatedAddress != dev.address) return
        json.put("seq", txSeq.getAndIncrement())
        val key = BleCrypto.getOrCreateSharedKey(this)
        val encrypted = BleCrypto.encrypt(key, json.toString().toByteArray(Charsets.UTF_8))
        sendFramedNotification(dev, encrypted, BleFraming.FLAG_ENCRYPTED)
    }

    @SuppressLint("MissingPermission")
    private fun sendFramedNotification(device: BluetoothDevice, payload: ByteArray, flags: Byte) {
        val ch = notifyCharacteristic ?: return
        val chunks = bleFraming.encode(payload, flags, negotiatedMtu)
        if (chunks.isEmpty()) return

        // Serialize multi-chunk notifications with Mutex so concurrent messages never interleave
        serviceScope.launch {
            bleNotifyMutex.withLock {
                for (chunk in chunks) {
                    runCatching {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            gattServer?.notifyCharacteristicChanged(device, ch, false, chunk)
                        } else {
                            @Suppress("DEPRECATION")
                            ch.value = chunk
                            @Suppress("DEPRECATION")
                            gattServer?.notifyCharacteristicChanged(device, ch, false)
                        }
                    }
                    if (chunks.size > 1) delay(15)
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopBle() {
        runCatching {
            val btManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
            advertiseCallback?.let { btManager.adapter?.bluetoothLeAdvertiser?.stopAdvertising(it) }
        }
        advertiseCallback = null
        runCatching { gattServer?.close() }
        gattServer = null
    }

    private fun buildNotification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "SimBridge Service", NotificationManager.IMPORTANCE_LOW)
        )
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("SimBridge")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_phone_call)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        closeActiveCallAudio()
        releaseCallWakeLock()
        unregisterCallStateObserver()
        unregisterBluetoothStateReceiver()
        BridgeEventBus.removeShellListener(shellListener)
        adbManager.stopDiscovery()
        internetServer.stop()
        stopBle()
        serviceScope.cancel()
        isRunning = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
