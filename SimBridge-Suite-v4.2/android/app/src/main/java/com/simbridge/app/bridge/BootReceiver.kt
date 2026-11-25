package com.simbridge.app.bridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.simbridge.app.service.BridgeService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            val prefs = context.getSharedPreferences("simbridge_prefs", Context.MODE_PRIVATE)
            if (!prefs.getBoolean("user_stopped", false)) {
                val svc = Intent(context, BridgeService::class.java)
                context.startForegroundService(svc)
            }
        }
    }
}
