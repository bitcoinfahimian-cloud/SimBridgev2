package com.simbridge.app.adb

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.simbridge.app.bridge.BridgeEventBus
import com.simbridge.app.call.CompanionInCallService
import com.simbridge.app.shell.ShellBinderProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.security.SecureRandom

class AdbWirelessManager(private val context: Context) {

    companion object {
        const val CHANNEL_ID = "adb_pairing_channel"
        const val NOTIF_ID = 2001
        const val ACTION_SUBMIT_CODE = "com.simbridge.app.ACTION_SUBMIT_PAIR_CODE"
        const val KEY_PAIR_CODE = "key_pair_code"

        @Volatile var lastPairingPort: Int = -1
        @Volatile var lastConnectPort: Int = -1
    }

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var pairListener: NsdManager.DiscoveryListener? = null
    private var connListener: NsdManager.DiscoveryListener? = null

    fun startDiscovery(showNotification: Boolean = true, onPortsUpdated: (pairPort: Int, connPort: Int) -> Unit) {
        stopDiscovery()
        if (showNotification) {
            pairListener = startServiceDiscovery("_adb-tls-pairing._tcp") { port ->
                lastPairingPort = port
                onPortsUpdated(lastPairingPort, lastConnectPort)
                showPairingNotification(port)
            }
        }
        connListener = startServiceDiscovery("_adb-tls-connect._tcp") { port ->
            lastConnectPort = port
            onPortsUpdated(lastPairingPort, lastConnectPort)
        }
        if (showNotification) {
            showPairingNotification(lastPairingPort)
        }
    }

    fun stopDiscovery() {
        pairListener?.let { runCatching { nsdManager.stopServiceDiscovery(it) } }
        connListener?.let { runCatching { nsdManager.stopServiceDiscovery(it) } }
        pairListener = null
        connListener = null
    }

    private fun startServiceDiscovery(serviceType: String, onResolved: (Int) -> Unit): NsdManager.DiscoveryListener {
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {}
            override fun onServiceFound(service: NsdServiceInfo) {
                @Suppress("DEPRECATION")
                nsdManager.resolveService(service, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {}
                    override fun onServiceResolved(info: NsdServiceInfo) {
                        if (info.port > 0) onResolved(info.port)
                    }
                })
            }
            override fun onServiceLost(service: NsdServiceInfo) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        runCatching {
            nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
        }
        return listener
    }

    fun showPairingNotification(port: Int) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Wireless Debugging Pairing", NotificationManager.IMPORTANCE_HIGH)
        )

        val remoteInput = RemoteInput.Builder(KEY_PAIR_CODE)
            .setLabel("6-digit code")
            .build()

        val intent = Intent(context, PairingCodeReceiver::class.java).apply {
            action = ACTION_SUBMIT_CODE
            putExtra("port", port)
        }
        val pi = PendingIntent.getBroadcast(
            context, 1001, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )

        val action = Notification.Action.Builder(null, "Pair", pi)
            .addRemoteInput(remoteInput)
            .build()

        val portInfo = if (port > 0) " (port $port)" else ""
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentTitle("SimBridge Wireless Debugging$portInfo")
            .setContentText("Tap Pair and type the 6-digit code shown on screen.")
            .addAction(action)
            .setOngoing(true)
            .build()

        nm.notify(NOTIF_ID, notification)
    }

    suspend fun pairAndLaunchShell(sixDigitCode: String, pairPortHint: Int = -1): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val pPort = if (pairPortHint > 0) pairPortHint else lastPairingPort
                require(pPort > 0) { "Wireless debugging pairing port not discovered yet. Keep the code dialog open." }

                val client = AdbTlsClient(context)
                client.pair("127.0.0.1", pPort, sixDigitCode)

                repeat(12) {
                    if (lastConnectPort > 0) return@repeat
                    delay(400)
                }
                val cPort = lastConnectPort
                require(cPort > 0) { "Paired! Now turn Wireless debugging off & on or wait for connect port." }

                launchShellOnPort(client, cPort)
            }
        }

    suspend fun reconnectAndLaunchShell(): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val cPort = lastConnectPort
            require(cPort > 0) { "No Wireless debugging connect port found yet." }
            launchShellOnPort(AdbTlsClient(context), cPort)
        }
    }

    private suspend fun launchShellOnPort(client: AdbTlsClient, connectPort: Int): String {
        val token = generateRandomTokenHex()
        ShellBinderProvider.expectedToken = token

        val cmd = buildShellLaunchCommand(token)
        val out = client.runShellCommand("127.0.0.1", connectPort, cmd)

        // Fix #5: Parse actual Telecom companion registration result instead of discarding stderr/stdout
        parseCompanionRegistrationOutput(out)

        repeat(20) {
            if (ShellBinderProvider.shellTap != null) {
                return "Shell bound (UID ${ShellBinderProvider.shellTap?.uid}) | Companion: ${CompanionInCallService.registrationCheckDetail}"
            }
            delay(200)
        }
        return "Shell command sent (output: ${out.trim().take(160)})"
    }

    private fun parseCompanionRegistrationOutput(rawOut: String) {
        val pkg = context.packageName
        val rcLine = rawOut.lineSequence().firstOrNull { it.startsWith("COMPANION_RC=") }
        val dumpLine = rawOut.lineSequence().firstOrNull { it.startsWith("COMPANION_DUMPSYS=") }

        val rcVal = rcLine?.substringAfter("COMPANION_RC=")?.trim()?.toIntOrNull()
        val dumpText = dumpLine?.substringAfter("COMPANION_DUMPSYS=")?.trim().orEmpty()
        val foundInDumpsys = dumpText.contains(pkg)

        CompanionInCallService.registeredInTelecom = (rcVal == 0) || foundInDumpsys
        CompanionInCallService.registrationCheckDetail = when {
            foundInDumpsys -> "Verified in dumpsys telecom (rc=${rcVal ?: "?"})"
            rcVal == 0 -> "Command rc=0 (awaiting first call to verify bind)"
            else -> "Not confirmed (rc=${rcVal ?: "?"}, dumpsys empty; fallback TelephonyCallback active)"
        }
        BridgeEventBus.notifyCompanionDiagChanged()
    }

    fun buildShellLaunchCommand(token: String): String {
        val pkg = context.packageName
        return "pm grant $pkg android.permission.WRITE_SECURE_SETTINGS 2>&1; " +
            "cmd appops set $pkg ACCESS_RESTRICTED_SETTINGS allow 2>&1; " +
            "cmd telecom add-or-remove-call-companion-app $pkg 1 2>&1; " +
            "echo COMPANION_RC=\$?; " +
            "D=\$(dumpsys telecom 2>/dev/null | grep -iF '$pkg' | head -n 1); " +
            "echo COMPANION_DUMPSYS=\$D; " +
            "for d in /proc/[0-9]*; do n=; read -r n < \$d/comm 2>/dev/null; " +
            "[ \"\$n\" = simbridge_shell ] && kill \${d#/proc/} 2>/dev/null; done; " +
            "P=\$(pm path $pkg | head -n 1 | cut -d: -f2); " +
            "SIMBRIDGE_PKG=$pkg SIMBRIDGE_TOKEN=$token CLASSPATH=\$P " +
            "setsid app_process /system/bin --nice-name=simbridge_shell " +
            "com.simbridge.app.shell.ShellMain </dev/null >/data/local/tmp/simbridge-shell.log 2>&1 & " +
            "sleep 1; echo started"
    }

    private fun generateRandomTokenHex(): String {
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
