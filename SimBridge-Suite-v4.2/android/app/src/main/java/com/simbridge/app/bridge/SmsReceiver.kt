package com.simbridge.app.bridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArraySet

object BridgeEventBus {
    @Volatile var onOutgoingToIphone: ((JSONObject) -> Unit)? = null
    @Volatile var onCompanionCallEvent: ((state: String, number: String) -> Unit)? = null

    private val connectionListeners = CopyOnWriteArraySet<(Boolean, String) -> Unit>()
    private val shellListeners = CopyOnWriteArraySet<(Boolean) -> Unit>()
    private val audioStatsListeners = CopyOnWriteArraySet<(Long, Long) -> Unit>()
    private val companionDiagListeners = CopyOnWriteArraySet<() -> Unit>()

    fun addConnectionListener(l: (Boolean, String) -> Unit) { connectionListeners.add(l) }
    fun removeConnectionListener(l: (Boolean, String) -> Unit) { connectionListeners.remove(l) }
    fun notifyConnectionChanged(connected: Boolean, name: String) {
        connectionListeners.forEach { runCatching { it(connected, name) } }
    }

    fun addShellListener(l: (Boolean) -> Unit) { shellListeners.add(l) }
    fun removeShellListener(l: (Boolean) -> Unit) { shellListeners.remove(l) }
    fun notifyShellBound(bound: Boolean) {
        shellListeners.forEach { runCatching { it(bound) } }
    }

    fun addAudioStatsListener(l: (Long, Long) -> Unit) { audioStatsListeners.add(l) }
    fun removeAudioStatsListener(l: (Long, Long) -> Unit) { audioStatsListeners.remove(l) }
    fun notifyAudioStats(rx: Long, tx: Long) {
        audioStatsListeners.forEach { runCatching { it(rx, tx) } }
    }

    fun addCompanionDiagListener(l: () -> Unit) { companionDiagListeners.add(l) }
    fun removeCompanionDiagListener(l: () -> Unit) { companionDiagListeners.remove(l) }
    fun notifyCompanionDiagChanged() {
        companionDiagListeners.forEach { runCatching { it() } }
    }

    fun emitToIphone(json: JSONObject) {
        onOutgoingToIphone?.invoke(json)
    }
}

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        val sender = messages.firstOrNull()?.displayOriginatingAddress ?: return
        val body = messages.joinToString("") { it.displayMessageBody }
        val senderName = ContactResolver.lookupDisplayName(context, sender)
        val payload = JSONObject().apply {
            put("type", "sms_in")
            put("from", sender)
            put("sender_name", senderName)
            put("body", body)
            put("ts", System.currentTimeMillis())
        }
        BridgeEventBus.emitToIphone(payload)
    }
}
