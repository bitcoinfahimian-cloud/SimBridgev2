package com.simbridge.app.call

import android.telecom.Call
import android.telecom.InCallService
import android.telecom.VideoProfile
import com.simbridge.app.bridge.BridgeEventBus
import java.util.concurrent.atomic.AtomicInteger

/**
 * Bound by Android Telecom during calls once registered via:
 *   cmd telecom add-or-remove-call-companion-app com.simbridge.app 1
 *
 * Fix #5: Tracks real registration verification (from dumpsys telecom), actual onBind/onCreate count,
 * and real received call events instead of blindly assuming the ADB command succeeded.
 */
class CompanionInCallService : InCallService() {

    companion object {
        @Volatile var instance: CompanionInCallService? = null
        @Volatile var currentCall: Call? = null
        @Volatile var isBound: Boolean = false

        // Populated by AdbWirelessManager after checking `dumpsys telecom`
        @Volatile var registeredInTelecom: Boolean = false
        @Volatile var registrationCheckDetail: String = "Not checked yet"

        // Real runtime proof counters (only increment when Telecom actually binds & delivers Call events)
        val bindCount = AtomicInteger(0)
        val eventsReceivedCount = AtomicInteger(0)
        @Volatile var lastEventSummary: String = "No Telecom call event received yet"

        fun statusSummary(): String {
            return "telecomReg=$registeredInTelecom ($registrationCheckDetail) | boundNow=$isBound (binds=${bindCount.get()}) | events=${eventsReceivedCount.get()} [$lastEventSummary]"
        }

        fun answerCall(): Boolean {
            val c = currentCall ?: return false
            return runCatching {
                c.answer(VideoProfile.STATE_AUDIO_ONLY)
                true
            }.getOrDefault(false)
        }

        fun hangupCall(): Boolean {
            val c = currentCall ?: return false
            return runCatching {
                if (c.state == Call.STATE_RINGING) {
                    c.reject(false, null)
                } else {
                    c.disconnect()
                }
                true
            }.getOrDefault(false)
        }

        fun setCallMuted(muted: Boolean): Boolean {
            val svc = instance ?: return false
            return runCatching {
                svc.setMuted(muted)
                true
            }.getOrDefault(false)
        }

        fun sendDtmf(digit: Char): Boolean {
            val c = currentCall ?: return false
            return runCatching {
                c.playDtmfTone(digit)
                c.stopDtmfTone()
                true
            }.getOrDefault(false)
        }
    }

    private val callCallback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            emitState(call, state)
        }

        override fun onDetailsChanged(call: Call, details: Call.Details) {
            emitState(call, call.state)
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        isBound = true
        bindCount.incrementAndGet()
        BridgeEventBus.notifyCompanionDiagChanged()
    }

    override fun onDestroy() {
        currentCall?.unregisterCallback(callCallback)
        currentCall = null
        instance = null
        isBound = false
        BridgeEventBus.notifyCompanionDiagChanged()
        super.onDestroy()
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        currentCall?.unregisterCallback(callCallback)
        currentCall = call
        call.registerCallback(callCallback)
        emitState(call, call.state)
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        call.unregisterCallback(callCallback)
        if (currentCall == call) {
            currentCall = null
            recordEvent("IDLE", "")
            BridgeEventBus.onCompanionCallEvent?.invoke("IDLE", "")
        }
    }

    private fun emitState(call: Call, state: Int) {
        val number = call.details?.handle?.schemeSpecificPart ?: ""
        val mapped = when (state) {
            Call.STATE_RINGING -> "RINGING"
            Call.STATE_DIALING, Call.STATE_CONNECTING -> "DIALING"
            Call.STATE_ACTIVE -> "ACTIVE"
            Call.STATE_DISCONNECTED -> "IDLE"
            else -> return
        }
        recordEvent(mapped, number)
        BridgeEventBus.onCompanionCallEvent?.invoke(mapped, number)
    }

    private fun recordEvent(state: String, number: String) {
        eventsReceivedCount.incrementAndGet()
        val masked = if (number.length > 4) "****${number.takeLast(4)}" else number
        lastEventSummary = "$state ($masked)"
        BridgeEventBus.notifyCompanionDiagChanged()
    }
}
