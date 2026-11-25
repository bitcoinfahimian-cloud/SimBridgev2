package com.simbridge.app.shell

import android.content.AttributionSource
import android.content.Context
import android.content.ContextWrapper
import android.media.*
import android.os.*
import com.simbridge.app.IShellTap
import org.json.JSONObject
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

object ShellMain {
    @JvmStatic
    fun main(args: Array<String>) {
        Looper.prepareMainLooper()
        val pkg = System.getenv("SIMBRIDGE_PKG")?.takeIf { it.isNotBlank() } ?: run {
            System.err.println("SIMBRIDGE_PKG missing, exiting")
            Runtime.getRuntime().halt(1)
            return
        }
        val token = System.getenv("SIMBRIDGE_TOKEN")?.takeIf { it.isNotBlank() } ?: run {
            System.err.println("SIMBRIDGE_TOKEN missing, exiting")
            Runtime.getRuntime().halt(1)
            return
        }

        val shellContext = createShellAudioContext()
        val impl = ShellTapImpl(shellContext)
        val bound = deliverBinderToApp(pkg, token, impl)
        if (!bound) {
            System.err.println("Failed to bind_shell to $pkg, exiting")
            Runtime.getRuntime().halt(2)
            return
        }

        println("simbridge_shell v4 bound to $pkg (uid=${Process.myUid()}, hasCtx=${shellContext != null})")
        Looper.loop()
    }

    private fun createShellAudioContext(): Context? = runCatching {
        val atClass = Class.forName("android.app.ActivityThread")
        val at = atClass.getMethod("systemMain").invoke(null)
        val sysCtx = atClass.getMethod("getSystemContext").invoke(at) as Context
        val base = runCatching {
            sysCtx.createPackageContext("com.android.shell", Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY)
        }.getOrElse { sysCtx }

        object : ContextWrapper(base) {
            override fun getPackageName(): String = "com.android.shell"
            override fun getAttributionSource(): AttributionSource {
                return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    AttributionSource.Builder(Process.myUid())
                        .setPackageName("com.android.shell")
                        .build()
                } else {
                    super.getAttributionSource()
                }
            }
        }
    }.onFailure {
        it.printStackTrace(System.err)
    }.getOrNull()

    private fun deliverBinderToApp(pkg: String, token: String, binder: IBinder): Boolean {
        val authority = "$pkg.shell.provider"
        val clientToken = Binder()
        val extras = Bundle().apply {
            putString("token", token)
            putBinder("binder", binder)
        }

        return runCatching {
            val amClass = Class.forName("android.app.ActivityManager")
            val iAm = amClass.getMethod("getService").invoke(null)
                ?: error("IActivityManager unavailable")

            val holder = iAm.javaClass.getMethod(
                "getContentProviderExternal",
                String::class.java,
                Int::class.javaPrimitiveType,
                IBinder::class.java,
                String::class.java
            ).invoke(iAm, authority, 0, clientToken, "simbridge_shell")
                ?: error("ContentProviderHolder is null for $authority")

            val provider = holder.javaClass.getField("provider").get(holder)
                ?: error("IContentProvider is null")

            try {
                val reply = invokeProviderCall(provider, authority, "bind_shell", null, extras)
                reply?.getBoolean("ok", false) == true
            } finally {
                runCatching {
                    iAm.javaClass.getMethod(
                        "removeContentProviderExternalAsUser",
                        String::class.java,
                        IBinder::class.java,
                        Int::class.javaPrimitiveType
                    ).invoke(iAm, authority, clientToken, 0)
                }
            }
        }.onFailure {
            it.printStackTrace(System.err)
        }.getOrDefault(false)
    }

    private fun invokeProviderCall(
        provider: Any,
        authority: String,
        method: String,
        arg: String?,
        extras: Bundle
    ): Bundle? {
        val cls = provider.javaClass
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val attr = AttributionSource.Builder(Process.myUid())
                .setPackageName("com.android.shell")
                .build()
            val m = cls.methods.firstOrNull {
                it.name == "call" && it.parameterTypes.size == 5 &&
                    it.parameterTypes[0].name == "android.content.AttributionSource"
            }
            if (m != null) {
                return m.invoke(provider, attr, authority, method, arg, extras) as? Bundle
            }
        }
        val m30 = cls.methods.firstOrNull {
            it.name == "call" && it.parameterTypes.size == 6 &&
                it.parameterTypes[0] == String::class.java
        }
        if (m30 != null) {
            return m30.invoke(provider, "com.android.shell", null, authority, method, arg, extras) as? Bundle
        }
        val m29 = cls.methods.firstOrNull {
            it.name == "call" && it.parameterTypes.size == 5 &&
                it.parameterTypes[0] == String::class.java
        }
        if (m29 != null) {
            return m29.invoke(provider, "com.android.shell", authority, method, arg, extras) as? Bundle
        }
        error("No compatible IContentProvider.call signature found")
    }
}

/**
 * Optimized for Xiaomi Mi 10T Pro (Android 13 / Snapdragon 865):
 * - methodCode 1 (AUTO_TX): Pin AudioTrack to TYPE_TELEPHONY (18) + fallback incall_music_enabled=true
 * - methodCode 2 (QCOM_INCALL): Qualcomm HAL incall_music_enabled=true + USAGE_MEDIA
 * - methodCode 3 (MTK_BGS): MediaTek BGS HAL params
 * - methodCode 4 (ANDROID13_CALL_REDIRECT): AudioManager.MODE_CALL_REDIRECT (5) + TYPE_TELEPHONY TX
 */
class ShellTapImpl(private val shellContext: Context?) : IShellTap.Stub() {
    companion object {
        private const val METHOD_AUTO_TX = 1
        private const val METHOD_QCOM_INCALL = 2
        private const val METHOD_MTK_BGS = 3
        private const val METHOD_A13_CALL_REDIRECT = 4

        private const val DEVICE_TYPE_TELEPHONY_TX = 18 // AudioDeviceInfo.TYPE_TELEPHONY
        private const val MODE_CALL_REDIRECT = 5        // AudioManager.MODE_CALL_REDIRECT (API 33+)
    }

    private val running = AtomicBoolean(false)
    private var downlinkRecord: AudioRecord? = null
    private var uplinkTrack: AudioTrack? = null
    private var activeMethod: Int = METHOD_AUTO_TX
    private var savedMode: Int = AudioManager.MODE_NORMAL
    private var modeModified: Boolean = false
    private var savedCallVol: Int = -1

    private fun trySetContext(builder: Any, ctx: Context) {
        runCatching {
            builder.javaClass.getMethod("setContext", Context::class.java).invoke(builder, ctx)
        }
    }

    override fun getVersion(): String = "1.3.1-shell-v4.1"
    override fun getUid(): Int = Process.myUid()

    private fun buildDownlinkRecord(sampleRate: Int): AudioRecord {
        val recBuf = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(3200)

        val recBuilder = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_DOWNLINK)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build()
            )
            .setBufferSizeInBytes(recBuf)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && shellContext != null) {
            trySetContext(recBuilder, shellContext)
        }
        return recBuilder.build()
    }

    private fun buildUplinkTrack(methodCode: Int, sampleRate: Int, audioManager: AudioManager?): Pair<AudioTrack, Boolean> {
        val telephonyTxDevice = audioManager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            ?.firstOrNull { it.type == DEVICE_TYPE_TELEPHONY_TX }

        when (methodCode) {
            METHOD_QCOM_INCALL -> setAudioSystemParam("incall_music_enabled=true")
            METHOD_MTK_BGS -> setAudioSystemParam("Set_BGS_UL_Mute=1;Set_BGS_DL_Mute=0")
            METHOD_A13_CALL_REDIRECT -> {
                if (audioManager != null) {
                    savedMode = audioManager.mode
                    runCatching {
                        audioManager.mode = MODE_CALL_REDIRECT
                        modeModified = true
                    }
                }
            }
            else -> {
                if (telephonyTxDevice == null) {
                    setAudioSystemParam("incall_music_enabled=true")
                }
            }
        }

        val playBuf = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(3200)

        val attrsBuilder = AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)

        if ((methodCode == METHOD_AUTO_TX || methodCode == METHOD_A13_CALL_REDIRECT) && telephonyTxDevice != null) {
            attrsBuilder.setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
        } else {
            attrsBuilder.setUsage(AudioAttributes.USAGE_MEDIA)
        }

        val trackBuilder = AudioTrack.Builder()
            .setAudioAttributes(attrsBuilder.build())
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(playBuf)
            .setTransferMode(AudioTrack.MODE_STREAM)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && shellContext != null) {
            trySetContext(trackBuilder, shellContext)
        }
        val track = trackBuilder.build()
        var pinned = false
        if (telephonyTxDevice != null && (methodCode == METHOD_AUTO_TX || methodCode == METHOD_A13_CALL_REDIRECT)) {
            pinned = runCatching { track.setPreferredDevice(telephonyTxDevice) }.getOrDefault(false)
        }
        return track to pinned
    }

    override fun openCallAudio(methodCode: Int, sampleRate: Int): Array<ParcelFileDescriptor> {
        closeCallAudio()
        running.set(true)
        activeMethod = methodCode

        val downPipe = ParcelFileDescriptor.createPipe()
        val upPipe = ParcelFileDescriptor.createPipe()

        val audioManager = shellContext?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

        // Quiet local Android earpiece so people nearby don't hear the call & prevent acoustic bleed
        if (audioManager != null) {
            runCatching {
                savedCallVol = audioManager.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
                val minVol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    audioManager.getStreamMinVolume(AudioManager.STREAM_VOICE_CALL)
                } else 0
                audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, minVol, 0)
            }
        }

        downlinkRecord = buildDownlinkRecord(sampleRate)
        val (track, _) = buildUplinkTrack(methodCode, sampleRate, audioManager)
        uplinkTrack = track

        // Mute Android local mic on QCOM/A13 paths so bag/pocket noise isn't mixed into the call
        if (methodCode == METHOD_QCOM_INCALL || methodCode == METHOD_A13_CALL_REDIRECT) {
            setLocalMicMute(true)
        }

        Thread {
            val out = FileOutputStream(downPipe[1].fileDescriptor)
            val buf = ByteArray(640)
            runCatching { downlinkRecord?.startRecording() }
            while (running.get()) {
                val n = downlinkRecord?.read(buf, 0, buf.size) ?: -1
                if (n > 0 && runCatching { out.write(buf, 0, n) }.isFailure) break
            }
            runCatching { out.close(); downPipe[1].close() }
        }.start()

        Thread {
            val input = FileInputStream(upPipe[0].fileDescriptor)
            val buf = ByteArray(640)
            runCatching { uplinkTrack?.play() }
            while (running.get()) {
                val n = runCatching { input.read(buf) }.getOrDefault(-1)
                if (n <= 0) break
                uplinkTrack?.write(buf, 0, n)
            }
            runCatching { input.close(); upPipe[0].close() }
        }.start()

        return arrayOf(downPipe[0], upPipe[1])
    }

    override fun setLocalMicMute(muted: Boolean) {
        val am = shellContext?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        runCatching { am.isMicrophoneMute = muted }
    }

    /**
     * Real hardware diagnostic probe (Stage 4 proof — NO mocking):
     * During a live SIM call on Mi 10T Pro:
     * 1) Records VOICE_DOWNLINK for durationMs and computes actual RMS dBFS & non-zero sample ratio.
     * 2) Injects a 1000 Hz sine test tone into uplink using the selected methodCode so the remote party can confirm hearing it.
     */
    override fun probeCallAudio(methodCode: Int, durationMs: Int): String {
        val sampleRate = 16000
        val dur = durationMs.coerceIn(1000, 4000)
        val audioManager = shellContext?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val telephonyTxFound = audioManager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            ?.any { it.type == DEVICE_TYPE_TELEPHONY_TX } == true

        val res = JSONObject()
        res.put("method", methodCode)
        res.put("telephony_tx_found", telephonyTxFound)
        res.put("audio_mode_before", audioManager?.mode ?: -1)

        // Close any active tap first so VOICE_DOWNLINK does not collide with itself
        val hadActiveCallAudio = running.get()
        if (hadActiveCallAudio) closeCallAudio()

        var rec: AudioRecord? = null
        var trk: AudioTrack? = null
        var toneThread: Thread? = null
        val probeRunning = AtomicBoolean(true)
        try {
            rec = buildDownlinkRecord(sampleRate)
            val (builtTrack, pinned) = buildUplinkTrack(methodCode, sampleRate, audioManager)
            trk = builtTrack
            res.put("tx_pinned", pinned)
            res.put("record_state", rec.state)
            res.put("track_state", trk.state)

            rec.startRecording()
            trk.play()

            // Generate 20ms 1kHz test tone chunk (320 samples @ 16kHz = 640 bytes)
            val toneBytes = ByteArray(640)
            val toneBb = ByteBuffer.wrap(toneBytes).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until 320) {
                val s = (sin(2.0 * PI * 1000.0 * i / sampleRate) * 10000.0).toInt().toShort()
                toneBb.putShort(s)
            }

            val rxBuf = ByteArray(640)
            val silenceBytes = ByteArray(640)
            var sumSq = 0.0
            var totalSamples = 0L
            var nonZeroSamples = 0L
            val loops = dur / 20

            // Run tone writer concurrently so blocking AudioRecord.read never stutters the uplink tone
            val writerTrack = trk
            toneThread = Thread {
                for (i in 0 until loops) {
                    if (!probeRunning.get()) break
                    if ((i % 50) < 25) {
                        writerTrack.write(toneBytes, 0, toneBytes.size)
                    } else {
                        writerTrack.write(silenceBytes, 0, silenceBytes.size)
                    }
                }
            }.apply { start() }

            for (i in 0 until loops) {
                val n = rec.read(rxBuf, 0, rxBuf.size)
                if (n > 1) {
                    val rxBb = ByteBuffer.wrap(rxBuf, 0, n).order(ByteOrder.LITTLE_ENDIAN)
                    while (rxBb.remaining() >= 2) {
                        val v = rxBb.short.toInt()
                        if (v != 0) nonZeroSamples++
                        sumSq += v.toDouble() * v.toDouble()
                        totalSamples++
                    }
                }
            }
            probeRunning.set(false)
            runCatching { toneThread.join(500) }

            val rms = if (totalSamples > 0) sqrt(sumSq / totalSamples) else 0.0
            val dbfs = if (rms > 1.0) 20.0 * log10(rms / 32768.0) else -96.0
            val downlinkActive = nonZeroSamples > 100 && dbfs > -65.0

            res.put("ok", true)
            res.put("samples_read", totalSamples)
            res.put("non_zero_samples", nonZeroSamples)
            res.put("downlink_dbfs", String.format(Locale.US, "%.1f", dbfs).toDouble())
            res.put("downlink_active", downlinkActive)
        } catch (t: Throwable) {
            res.put("ok", false)
            res.put("error", "${t.javaClass.simpleName}: ${t.message}")
        } finally {
            probeRunning.set(false)
            runCatching { toneThread?.join(300) }
            setAudioSystemParam("incall_music_enabled=false")
            if (methodCode == METHOD_MTK_BGS) {
                setAudioSystemParam("Set_BGS_UL_Mute=0;Set_BGS_DL_Mute=1")
            }
            if (modeModified && audioManager != null) {
                runCatching { audioManager.mode = savedMode }
                modeModified = false
            }
            runCatching { rec?.stop(); rec?.release() }
            runCatching { trk?.stop(); trk?.release() }
        }
        return res.toString()
    }

    override fun closeCallAudio() {
        running.set(false)
        setLocalMicMute(false)
        setAudioSystemParam("incall_music_enabled=false")
        if (activeMethod == METHOD_MTK_BGS) {
            setAudioSystemParam("Set_BGS_UL_Mute=0;Set_BGS_DL_Mute=1")
        }
        val audioManager = shellContext?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (modeModified && audioManager != null) {
            runCatching { audioManager.mode = savedMode }
            modeModified = false
        }
        if (savedCallVol >= 0 && audioManager != null) {
            runCatching { audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, savedCallVol, 0) }
            savedCallVol = -1
        }
        runCatching { downlinkRecord?.stop(); downlinkRecord?.release() }
        runCatching { uplinkTrack?.stop(); uplinkTrack?.release() }
        downlinkRecord = null
        uplinkTrack = null
    }

    override fun exitShell() {
        closeCallAudio()
        Runtime.getRuntime().halt(0)
    }

    private fun setAudioSystemParam(kv: String) {
        runCatching {
            val cls = Class.forName("android.media.AudioSystem")
            cls.getMethod("setParameters", String::class.java).invoke(null, kv)
        }
    }
}
