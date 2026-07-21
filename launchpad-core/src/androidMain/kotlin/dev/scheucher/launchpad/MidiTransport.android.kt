package dev.scheucher.launchpad

import android.content.Context
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo as AndroidMidiDeviceInfo
import android.media.midi.MidiInputPort
import android.media.midi.MidiManager
import android.media.midi.MidiOutputPort
import android.media.midi.MidiReceiver
import android.os.Handler
import android.os.Looper

/**
 * Android MIDI transport backed by `android.media.midi.MidiManager` (USB host mode, API 23+).
 *
 * A Launchpad plugged into a USB-C phone that supports USB-OTG shows up as a class-compliant MIDI
 * device. This transport enumerates such devices, opens the first input+output port pair, and
 * bridges bytes to/from the common [MidiProtocol].
 *
 * Because Android's MIDI API needs a [Context] and the common `expect class` constructor takes no
 * arguments, the host app must call [LaunchpadAndroid.init] once (e.g. in Application.onCreate)
 * before constructing a [Launchpad]. Device open is asynchronous on Android; [open] blocks briefly
 * for the callback to keep the common API simple.
 */
actual class MidiTransport actual constructor() {

    private val appContext: Context =
        LaunchpadAndroid.context ?: error(
            "LaunchpadAndroid.init(context) must be called before using MidiTransport on Android"
        )
    private val manager: MidiManager =
        appContext.getSystemService(Context.MIDI_SERVICE) as MidiManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var device: MidiDevice? = null
    private var inputPort: MidiInputPort? = null      // host -> device (we send here)
    private var outputPort: MidiOutputPort? = null    // device -> host (we receive here)
    private var onMessage: ((ByteArray) -> Unit)? = null

    actual fun listDevices(): List<MidiDeviceInfo> =
        manager.devices.mapNotNull { info ->
            val name = deviceName(info) ?: return@mapNotNull null
            if (!name.lowercase().contains("launchpad")) return@mapNotNull null
            // id encodes the Android device id so open() can find it again.
            MidiDeviceInfo(id = info.id.toString(), name = name)
        }

    actual fun open(deviceId: String) {
        close()
        val target = manager.devices.firstOrNull { it.id.toString() == deviceId }
            ?: error("MIDI device '$deviceId' not found")

        val latch = java.util.concurrent.CountDownLatch(1)
        manager.openDevice(target, { opened ->
            device = opened
            if (opened != null) {
                // Open the first input port (to send) and first output port (to receive).
                val inPortInfo = target.ports.firstOrNull { it.type == AndroidMidiDeviceInfo.PortInfo.TYPE_INPUT }
                val outPortInfo = target.ports.firstOrNull { it.type == AndroidMidiDeviceInfo.PortInfo.TYPE_OUTPUT }
                inPortInfo?.let { inputPort = opened.openInputPort(it.portNumber) }
                outPortInfo?.let { pi ->
                    outputPort = opened.openOutputPort(pi.portNumber)?.also { op ->
                        op.connect(object : MidiReceiver() {
                            override fun onSend(msg: ByteArray, offset: Int, count: Int, timestamp: Long) {
                                onMessage?.invoke(msg.copyOfRange(offset, offset + count))
                            }
                        })
                    }
                }
            }
            latch.countDown()
        }, mainHandler)

        // Block briefly for the async open so the common API stays synchronous.
        latch.await(3, java.util.concurrent.TimeUnit.SECONDS)
        checkNotNull(inputPort) { "Failed to open MIDI input port for '$deviceId'" }
    }

    actual fun isOpen(): Boolean = inputPort != null

    actual fun send(message: ByteArray) {
        val port = inputPort ?: error("Transport not open")
        port.send(message, 0, message.size)
        // Match the JVM transport: pace bulk sends so a 64-pad repaint isn't dropped/reordered by the
        // Android MIDI stack, which delivers asynchronously.
        runCatching { Thread.sleep(0, 400_000) } // 0.4 ms
    }

    actual fun setReceiver(onMessage: ((ByteArray) -> Unit)?) { this.onMessage = onMessage }

    actual fun close() {
        runCatching { inputPort?.close() }
        runCatching { outputPort?.close() }
        runCatching { device?.close() }
        inputPort = null; outputPort = null; device = null
    }

    private fun deviceName(info: AndroidMidiDeviceInfo): String? {
        val props = info.properties
        return props.getString(AndroidMidiDeviceInfo.PROPERTY_NAME)
            ?: props.getString(AndroidMidiDeviceInfo.PROPERTY_PRODUCT)
    }
}

/** One-time Android setup: hand the library an application [Context] for MIDI access. */
object LaunchpadAndroid {
    @Volatile internal var context: Context? = null
    fun init(context: Context) { this.context = context.applicationContext }
}

actual fun settleAfterModeSwitch() {
    runCatching { Thread.sleep(80) }
}
