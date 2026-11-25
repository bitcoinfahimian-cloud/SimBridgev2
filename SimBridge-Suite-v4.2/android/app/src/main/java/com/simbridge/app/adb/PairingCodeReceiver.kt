package com.simbridge.app.adb

import android.app.NotificationManager
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class PairingCodeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val results = RemoteInput.getResultsFromIntent(intent) ?: return
        val code = results.getCharSequence(AdbWirelessManager.KEY_PAIR_CODE)?.toString()?.trim() ?: return
        val port = intent.getIntExtra("port", AdbWirelessManager.lastPairingPort)

        if (code.length != 6 || !code.all { it.isDigit() }) {
            Toast.makeText(context, "Please enter a valid 6-digit pairing code", Toast.LENGTH_SHORT).show()
            return
        }

        val pendingResult = goAsync()
        val appCtx = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val mgr = AdbWirelessManager(appCtx)
                val outcome = mgr.pairAndLaunchShell(code, port)
                val nm = appCtx.getSystemService(NotificationManager::class.java)
                val msg = outcome.fold(
                    onSuccess = {
                        nm.cancel(AdbWirelessManager.NOTIF_ID)
                        "Wireless debugging paired: $it"
                    },
                    onFailure = {
                        "Pairing failed: ${it.message ?: it.javaClass.simpleName}"
                    }
                )
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(appCtx, msg, Toast.LENGTH_LONG).show()
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
